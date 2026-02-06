package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.modes.ctf.CtfRules;
import com.Chris__.realm_ruler.ui.CtfFlagsHudState;
import com.Chris__.realm_ruler.world.StandSwapService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CtfFlagStateService {

    public sealed interface FlagState permits FlagState.InStand, FlagState.Held {
        record InStand(@Nullable StandLocation location, @Nullable String baseTeamName) implements FlagState {
        }

        record Held(String holderUuid, @Nullable String holderName) implements FlagState {
        }
    }

    public record StandLocation(String worldName, int x, int y, int z) {
    }

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final HytaleLogger logger;

    private final Map<CtfMatchService.Team, FlagState> stateByFlag = new EnumMap<>(CtfMatchService.Team.class);

    public CtfFlagStateService(CtfMatchService matchService, SimpleClaimsCtfBridge simpleClaims, HytaleLogger logger) {
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.logger = logger;
        resetForNewMatch();
    }

    public void resetForNewMatch() {
        stateByFlag.put(CtfMatchService.Team.RED, new FlagState.InStand(null, CtfMatchService.Team.RED.displayName()));
        stateByFlag.put(CtfMatchService.Team.BLUE, new FlagState.InStand(null, CtfMatchService.Team.BLUE.displayName()));
        stateByFlag.put(CtfMatchService.Team.YELLOW, new FlagState.InStand(null, CtfMatchService.Team.YELLOW.displayName()));
        stateByFlag.put(CtfMatchService.Team.WHITE, new FlagState.InStand(null, CtfMatchService.Team.WHITE.displayName()));
    }

    public void onWithdrawn(String holderUuid,
                            @Nullable String holderName,
                            String flagItemId,
                            String worldName,
                            int x,
                            int y,
                            int z) {
        CtfMatchService.Team flag = flagTeamFromItemId(flagItemId);
        if (flag == null) return;
        stateByFlag.put(flag, new FlagState.Held(holderUuid, holderName));

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] flag withdrawn flag=%s holder=%s @ %s(%d,%d,%d)",
                    flag.displayName(),
                    safeName(holderName, holderUuid),
                    worldName,
                    x, y, z);
        }
    }

    public void onDeposited(String depositorUuid,
                            String flagItemId,
                            String worldName,
                            int x,
                            int y,
                            int z) {
        CtfMatchService.Team flag = flagTeamFromItemId(flagItemId);
        if (flag == null) return;

        String baseTeamName = null;
        if (simpleClaims != null && simpleClaims.isAvailable()) {
            int chunkX = ChunkUtil.chunkCoordinate(x);
            int chunkZ = ChunkUtil.chunkCoordinate(z);
            baseTeamName = simpleClaims.getTeamForChunk(worldName, chunkX, chunkZ);
        }

        stateByFlag.put(flag, new FlagState.InStand(new StandLocation(worldName, x, y, z), baseTeamName));

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] flag deposited flag=%s base=%s by=%s @ %s(%d,%d,%d)",
                    flag.displayName(),
                    (baseTeamName == null) ? "<neutral>" : baseTeamName,
                    depositorUuid,
                    worldName,
                    x, y, z);
        }
    }

    public CtfFlagsHudState snapshotHudState() {
        return new CtfFlagsHudState(
                formatForHud(CtfMatchService.Team.RED),
                formatForHud(CtfMatchService.Team.BLUE),
                formatForHud(CtfMatchService.Team.YELLOW),
                formatForHud(CtfMatchService.Team.WHITE)
        );
    }

    public Map<String, Integer> computeScoresAtEnd() {
        Map<String, Integer> scores = new HashMap<>();
        for (CtfMatchService.Team flag : CtfMatchService.Team.values()) {
            FlagState st = stateByFlag.get(flag);
            if (st instanceof FlagState.InStand inStand) {
                String base = inStand.baseTeamName();
                if (base != null && !base.isBlank()) {
                    scores.merge(base, 1, Integer::sum);
                }
            }
        }
        return scores;
    }

    public void cleanupAfterMatch(StandSwapService standSwapService,
                                  Map<String, Player> playerByUuid,
                                  Iterable<String> matchUuids) {
        // Remove any flag items from match participants.
        if (playerByUuid != null && matchUuids != null) {
            for (String uuid : matchUuids) {
                if (uuid == null || uuid.isBlank()) continue;
                Player p = playerByUuid.get(uuid);
                if (p == null) continue;
                removeAllFlagsFromPlayer(p);
            }
        }

        // Reset stands holding captured flags (flags in a non-home base).
        if (standSwapService != null) {
            for (Map.Entry<CtfMatchService.Team, FlagState> e : stateByFlag.entrySet()) {
                CtfMatchService.Team flag = e.getKey();
                FlagState st = e.getValue();
                if (!(st instanceof FlagState.InStand inStand)) continue;

                StandLocation loc = inStand.location();
                if (loc == null) continue;

                String base = inStand.baseTeamName();
                boolean isHome = (base != null) && base.equalsIgnoreCase(flag.displayName());
                if (isHome) continue;

                World world = Universe.get().getWorld(loc.worldName());
                if (world == null) continue;
                standSwapService.swapStand(world, loc.x(), loc.y(), loc.z(), CtfRules.STAND_EMPTY);
            }
        }
    }

    private String formatForHud(CtfMatchService.Team flag) {
        FlagState st = stateByFlag.get(flag);
        if (st == null) return "Neutral";

        if (st instanceof FlagState.InStand inStand) {
            String base = inStand.baseTeamName();
            return (base == null || base.isBlank()) ? "Neutral" : base;
        }

        if (st instanceof FlagState.Held held) {
            String holderName = safeName(held.holderName(), held.holderUuid());
            CtfMatchService.Team holderTeam = (matchService == null) ? null : matchService.activeMatchTeamFor(held.holderUuid());
            if (holderTeam != null && holderTeam.displayName() != null && !holderTeam.displayName().isBlank()) {
                return holderTeam.displayName() + " - " + holderName;
            }
            return holderName;
        }

        return "Neutral";
    }

    private static @Nullable CtfMatchService.Team flagTeamFromItemId(String flagItemId) {
        if (flagItemId == null) return null;
        return switch (flagItemId) {
            case CtfRules.FLAG_RED -> CtfMatchService.Team.RED;
            case CtfRules.FLAG_BLUE -> CtfMatchService.Team.BLUE;
            case CtfRules.FLAG_YELLOW -> CtfMatchService.Team.YELLOW;
            case CtfRules.FLAG_WHITE -> CtfMatchService.Team.WHITE;
            default -> null;
        };
    }

    private static String safeName(@Nullable String name, String uuid) {
        if (name != null && !name.isBlank()) return name;
        if (uuid == null) return "<player>";
        String u = uuid.trim();
        if (u.isEmpty()) return "<player>";
        return (u.length() <= 8) ? u : u.substring(0, 8);
    }

    private static void removeAllFlagsFromPlayer(Player player) {
        if (player == null) return;
        Inventory inv = player.getInventory();
        if (inv == null) return;

        removeFlagsFromContainer(inv.getHotbar());
        removeFlagsFromContainer(inv.getStorage());
        removeFlagsFromContainer(inv.getBackpack());
        removeFlagsFromContainer(inv.getTools());
        removeFlagsFromContainer(inv.getUtility());
        removeFlagsFromContainer(inv.getArmor());

        player.sendInventory();
    }

    private static void removeFlagsFromContainer(ItemContainer container) {
        if (container == null) return;

        List<Short> slots = new ArrayList<>();
        container.forEach((slot, stack) -> {
            if (stack == null) return;
            if (CtfRules.isCustomFlagId(stack.getItemId())) {
                slots.add(slot);
            }
        });

        for (short slot : slots) {
            ItemStack stack = container.getItemStack(slot);
            if (stack == null) continue;
            if (!CtfRules.isCustomFlagId(stack.getItemId())) continue;
            int qty = Math.max(1, stack.getQuantity());
            container.removeItemStackFromSlot(slot, qty);
        }
    }
}
