package com.Chris__.realm_ruler.match;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class CtfShopConfigRepository {

    private static final String FILE_NAME = "ctf_shop.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private volatile CtfShopConfig cached = null;

    public CtfShopConfigRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        reload();
    }

    public CtfShopConfig getConfig() {
        CtfShopConfig c = cached;
        if (c != null) return c;
        c = defaultConfig();
        cached = c;
        return c;
    }

    public void reload() {
        if (filePath == null) {
            cached = defaultConfig();
            return;
        }

        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                CtfShopConfig cfg = defaultConfig();
                cached = cfg;
                save(cfg);
                return;
            }

            try (Reader r = Files.newBufferedReader(filePath)) {
                CtfShopConfig loaded = gson.fromJson(r, CtfShopConfig.class);
                if (loaded == null) {
                    CtfShopConfig cfg = defaultConfig();
                    cached = cfg;
                    save(cfg);
                    return;
                }

                if (shouldAutoMigrateToDefaults(loaded)) {
                    Path backup = backupCurrentConfig();
                    CtfShopConfig cfg = defaultConfig();
                    cached = cfg;
                    save(cfg);
                    if (backup != null) {
                        logger.atInfo().log("[RR-CTF] Migrated legacy ctf_shop.json to defaults. Backup created at %s", backup);
                    } else {
                        logger.atInfo().log("[RR-CTF] Migrated legacy ctf_shop.json to defaults.");
                    }
                    return;
                }

                cached = loaded;
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to load ctf shop config; using defaults.");
            cached = defaultConfig();
        }
    }

    private void save(CtfShopConfig cfg) {
        if (filePath == null || cfg == null) return;
        try (Writer w = Files.newBufferedWriter(filePath)) {
            gson.toJson(cfg, w);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to save default ctf shop config.");
        }
    }

    private static CtfShopConfig defaultConfig() {
        CtfShopConfig cfg = new CtfShopConfig();
        cfg.version = 2;

        cfg.items.add(singleItem("bread_pack", "Bread x2", 10, "Food_Bread", 2, "any", "any", null));
        cfg.items.add(singleItem("wildmeat_pack", "Wildmeat x2", 18, "Food_Wildmeat_Cooked", 2, "any", "any", null));
        cfg.items.add(singleItem("cheese_pack", "Cheese x2", 16, "Food_Cheese", 2, "any", "any", null));
        cfg.items.add(singleItem("veggie_pack", "Cooked Veg x2", 16, "Food_Vegetable_Cooked", 2, "any", "any", null));

        cfg.items.add(singleItem("potion_small", "Small Health Potion", 30, "Potion_Health_Small", 1, "any", "any", null));
        cfg.items.add(singleItem("potion_standard", "Health Potion", 45, "Potion_Health", 1, "any", "any", null));
        cfg.items.add(singleItem("potion_large", "Large Health Potion", 80, "Potion_Health_Large", 1, "any", "any", null));
        cfg.items.add(singleItem("potion_greater", "Greater Health Potion", 110, "Potion_Health_Greater", 1, "any", "any", null));

        cfg.items.add(bundleItem("red_armor_set", "Red Armor Set", 120, "red",
                "Armor_Leather_Red_Head", "Armor_Leather_Red_Chest", "Armor_Leather_Red_Hands", "Armor_Leather_Red_Legs"));
        cfg.items.add(bundleItem("blue_armor_set", "Blue Armor Set", 120, "blue",
                "Armor_Leather_Blue_Head", "Armor_Leather_Blue_Chest", "Armor_Leather_Blue_Hands", "Armor_Leather_Blue_Legs"));
        cfg.items.add(bundleItem("yellow_armor_set", "Yellow Armor Set", 120, "yellow",
                "Armor_Leather_Yellow_Head", "Armor_Leather_Yellow_Chest", "Armor_Leather_Yellow_Hands", "Armor_Leather_Yellow_Legs"));
        cfg.items.add(bundleItem("white_armor_set", "White Armor Set", 120, "white",
                "Armor_Leather_White_Head", "Armor_Leather_White_Chest", "Armor_Leather_White_Hands", "Armor_Leather_White_Legs"));

        cfg.items.add(singleItem("flag_red", "Red Objective Flag", 200, "Realm_Ruler_Flag_Red", 1, "outside_match_only", "any", null));
        cfg.items.add(singleItem("flag_blue", "Blue Objective Flag", 200, "Realm_Ruler_Flag_Blue", 1, "outside_match_only", "any", null));
        cfg.items.add(singleItem("flag_yellow", "Yellow Objective Flag", 200, "Realm_Ruler_Flag_Yellow", 1, "outside_match_only", "any", null));
        cfg.items.add(singleItem("flag_white", "White Objective Flag", 200, "Realm_Ruler_Flag_White", 1, "outside_match_only", "any", null));
        return cfg;
    }

    private boolean shouldAutoMigrateToDefaults(CtfShopConfig cfg) {
        if (cfg == null) return true;
        if (cfg.items == null || cfg.items.isEmpty()) return true;

        if (cfg.items.size() == 1) {
            CtfShopConfig.ShopItem single = cfg.items.get(0);
            String id = (single == null || single.id == null) ? "" : single.id.trim();
            String itemId = (single == null || single.itemId == null) ? "" : single.itemId.trim();
            if ("example".equalsIgnoreCase(id) && ("REPLACE_ME".equalsIgnoreCase(itemId) || itemId.isBlank())) {
                return true;
            }
        }

        if (cfg.version >= 2) {
            return false;
        }

        for (CtfShopConfig.ShopItem item : cfg.items) {
            if (item == null) continue;
            if (item.id == null || item.id.isBlank()) continue;
            boolean hasDirectReward = item.itemId != null && !item.itemId.isBlank();
            boolean hasBundleReward = item.bundleItems != null && !item.bundleItems.isEmpty();
            if (hasDirectReward || hasBundleReward) {
                return false;
            }
        }

        return true;
    }

    private Path backupCurrentConfig() {
        if (filePath == null) return null;
        try {
            if (!Files.exists(filePath)) return null;
            Path backup = filePath.resolveSibling(FILE_NAME + ".bak." + Instant.now().toEpochMilli());
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to back up legacy ctf_shop.json before migration.");
            return null;
        }
    }

    private static CtfShopConfig.ShopItem singleItem(String id,
                                                     String name,
                                                     int cost,
                                                     String itemId,
                                                     int amount,
                                                     String availability,
                                                     String teamRule,
                                                     String team) {
        CtfShopConfig.ShopItem item = new CtfShopConfig.ShopItem();
        item.id = id;
        item.enabled = true;
        item.name = name;
        item.cost = cost;
        item.type = "item";
        item.itemId = itemId;
        item.amount = Math.max(1, amount);
        item.availability = availability;
        item.teamRule = teamRule;
        item.team = team;
        return item;
    }

    private static CtfShopConfig.ShopItem bundleItem(String id,
                                                     String name,
                                                     int cost,
                                                     String team,
                                                     String... itemIds) {
        CtfShopConfig.ShopItem item = singleItem(id, name, cost, null, 1, "any", "own_team", team);
        item.type = "bundle";
        if (itemIds != null) {
            for (String itemId : itemIds) {
                if (itemId == null || itemId.isBlank()) continue;
                CtfShopConfig.BundleItem bundle = new CtfShopConfig.BundleItem();
                bundle.itemId = itemId;
                bundle.amount = 1;
                item.bundleItems.add(bundle);
            }
        }
        return item;
    }
}
