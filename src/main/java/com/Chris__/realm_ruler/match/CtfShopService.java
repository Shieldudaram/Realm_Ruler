package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.modes.ctf.CtfRules;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

public final class CtfShopService {

    public record RewardSpec(String itemId, int amount) {
    }

    public record ShopItemView(String id,
                               String name,
                               int cost,
                               String availability,
                               String teamRule,
                               String team,
                               List<RewardSpec> rewards) {
    }

    public record PurchaseResult(boolean success, String message, ShopItemView item, int remainingPoints) {
    }

    private final CtfMatchService matchService;
    private final CtfPointsRepository pointsRepository;
    private final CtfShopConfigRepository shopConfigRepository;
    private final BiFunction<String, Integer, ItemStack> itemStackFactory;
    private final HytaleLogger logger;

    public CtfShopService(CtfMatchService matchService,
                          CtfPointsRepository pointsRepository,
                          CtfShopConfigRepository shopConfigRepository,
                          BiFunction<String, Integer, ItemStack> itemStackFactory,
                          HytaleLogger logger) {
        this.matchService = matchService;
        this.pointsRepository = pointsRepository;
        this.shopConfigRepository = shopConfigRepository;
        this.itemStackFactory = itemStackFactory;
        this.logger = logger;
    }

    public List<ShopItemView> listEnabledItems() {
        List<ShopItemView> out = new ArrayList<>();
        CtfShopConfig cfg = (shopConfigRepository == null) ? null : shopConfigRepository.getConfig();
        if (cfg == null || cfg.items == null) return out;

        for (CtfShopConfig.ShopItem item : cfg.items) {
            if (item == null || !item.enabled) continue;
            if (item.id == null || item.id.isBlank()) continue;
            out.add(toView(item));
        }
        return out;
    }

    public int getPoints(String uuid) {
        if (pointsRepository == null || uuid == null || uuid.isBlank()) return 0;
        return pointsRepository.getPoints(uuid);
    }

    public void reloadCatalog() {
        if (shopConfigRepository == null) return;
        shopConfigRepository.reload();
    }

    public ShopItemView describeItem(String itemId) {
        CtfShopConfig.ShopItem item = findEnabledItemById(itemId);
        if (item == null) return null;
        return toView(item);
    }

    public PurchaseResult purchase(Player player, String uuid, String itemId) {
        if (player == null || uuid == null || uuid.isBlank()) {
            return new PurchaseResult(false, "Player not available.", null, -1);
        }
        if (pointsRepository == null || shopConfigRepository == null || itemStackFactory == null) {
            return new PurchaseResult(false, "CTF shop is not ready yet.", null, -1);
        }

        CtfShopConfig.ShopItem item = findEnabledItemById(itemId);
        if (item == null) {
            return new PurchaseResult(false, "That shop item is not available.", null, pointsRepository.getPoints(uuid));
        }

        int cost = Math.max(0, item.cost);
        int points = pointsRepository.getPoints(uuid);
        if (points < cost) {
            return new PurchaseResult(false, "Not enough CTF points. (" + points + "/" + cost + ")", toView(item), points);
        }

        boolean matchRunning = matchService != null && matchService.isRunning();
        CtfMatchService.Team playerTeam = (matchService == null) ? null : matchService.activeMatchTeamFor(uuid);
        String availabilityFailure = validateAvailability(item, matchRunning, playerTeam);
        if (availabilityFailure != null) {
            return new PurchaseResult(false, availabilityFailure, toView(item), points);
        }

        List<ItemStack> rewards = buildRewardStacks(item);
        if (rewards.isEmpty()) {
            return new PurchaseResult(false, "Shop item is misconfigured (missing rewards).", toView(item), points);
        }

        for (ItemStack reward : rewards) {
            if (reward == null || reward.getItemId() == null) continue;
            if (matchRunning && CtfRules.isCustomFlagId(reward.getItemId())) {
                return new PurchaseResult(false, "Objective flags can only be purchased outside active matches.", toView(item), points);
            }
        }

        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return new PurchaseResult(false, "Inventory not available.", toView(item), points);
        }
        ItemContainer combined = inventory.getCombinedBackpackStorageHotbar();
        if (combined == null) {
            return new PurchaseResult(false, "Inventory not available.", toView(item), points);
        }
        if (!combined.canAddItemStacks(rewards)) {
            return new PurchaseResult(false, "Inventory full.", toView(item), points);
        }

        if (!pointsRepository.spendPoints(uuid, cost)) {
            int remaining = pointsRepository.getPoints(uuid);
            return new PurchaseResult(false, "Not enough CTF points. (" + remaining + "/" + cost + ")", toView(item), remaining);
        }

        try {
            combined.addItemStacks(rewards);
            player.sendInventory();
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to grant purchased rewards. itemId=%s player=%s", item.id, uuid);
            pointsRepository.addPoints(uuid, cost);
            return new PurchaseResult(false, "Failed to grant purchased rewards. Try again.", toView(item), pointsRepository.getPoints(uuid));
        }

        int remaining = pointsRepository.getPoints(uuid);
        return new PurchaseResult(true, "Purchased: " + safe(item.name, item.id) + " (" + cost + " pts)", toView(item), remaining);
    }

    private CtfShopConfig.ShopItem findEnabledItemById(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        CtfShopConfig cfg = shopConfigRepository.getConfig();
        if (cfg == null || cfg.items == null) return null;
        for (CtfShopConfig.ShopItem item : cfg.items) {
            if (item == null || !item.enabled || item.id == null) continue;
            if (item.id.equalsIgnoreCase(itemId.trim())) {
                return item;
            }
        }
        return null;
    }

    private ShopItemView toView(CtfShopConfig.ShopItem item) {
        if (item == null) return null;
        return new ShopItemView(
                item.id,
                safe(item.name, item.id),
                Math.max(0, item.cost),
                normalize(item.availability, "any"),
                normalize(item.teamRule, "any"),
                normalize(item.team, ""),
                buildRewardSpecs(item)
        );
    }

    private String validateAvailability(CtfShopConfig.ShopItem item,
                                        boolean matchRunning,
                                        CtfMatchService.Team playerTeam) {
        String availability = normalize(item.availability, "any");
        switch (availability) {
            case "match_only" -> {
                if (!matchRunning || playerTeam == null) {
                    return "This item is only available to active CTF participants during a match.";
                }
            }
            case "outside_match_only" -> {
                if (matchRunning) {
                    return "This item is only available outside active matches.";
                }
            }
            default -> {
            }
        }

        String teamRule = normalize(item.teamRule, "any");
        if ("own_team".equals(teamRule) && matchRunning) {
            CtfMatchService.Team required = CtfMatchService.parseTeam(item.team);
            if (required != null && playerTeam != required) {
                return "This item can only be purchased by " + required.displayName() + " during a match.";
            }
        }

        return null;
    }

    private List<RewardSpec> buildRewardSpecs(CtfShopConfig.ShopItem item) {
        List<RewardSpec> out = new ArrayList<>();
        if (item == null) return out;

        if (item.bundleItems != null && !item.bundleItems.isEmpty()) {
            for (CtfShopConfig.BundleItem bundleItem : item.bundleItems) {
                if (bundleItem == null) continue;
                String rewardId = safe(bundleItem.itemId, "");
                if (rewardId.isBlank()) continue;
                out.add(new RewardSpec(rewardId, Math.max(1, bundleItem.amount)));
            }
            return out;
        }

        String rewardId = safe(item.itemId, "");
        if (rewardId.isBlank()) return out;
        out.add(new RewardSpec(rewardId, Math.max(1, item.amount)));
        return out;
    }

    private List<ItemStack> buildRewardStacks(CtfShopConfig.ShopItem item) {
        List<ItemStack> rewards = new ArrayList<>();
        if (item == null) return rewards;

        List<RewardSpec> specs = buildRewardSpecs(item);
        if (specs.isEmpty()) return rewards;

        for (RewardSpec spec : specs) {
            if (spec == null || spec.itemId == null || spec.itemId.isBlank()) continue;
            try {
                ItemStack stack = itemStackFactory.apply(spec.itemId, Math.max(1, spec.amount));
                if (stack == null) {
                    logger.atWarning().log("[RR-CTF] Shop reward item not found: %s", spec.itemId);
                    return List.of();
                }
                rewards.add(stack);
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[RR-CTF] Failed creating shop reward item: %s", spec.itemId);
                return List.of();
            }
        }

        return rewards;
    }

    private static String safe(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred.trim();
        return (fallback == null) ? "" : fallback.trim();
    }

    private static String normalize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
