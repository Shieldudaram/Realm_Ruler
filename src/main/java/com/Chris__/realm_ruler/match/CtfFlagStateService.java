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
import java.util.function.BiFunction;

public final class CtfFlagStateService {

    public sealed interface FlagState permits FlagState.InStand, FlagState.Held, FlagState.Dropped {
        record InStand(@Nullable StandLocation location, @Nullable String baseTeamName) implements FlagState {
        }

        record Held(String holderUuid, @Nullable String holderName, byte lockedHotbarSlot) implements FlagState {
        }

        record Dropped(String worldName,
                       double x,
                       double y,
                       double z,
                       long droppedAtNanos,
                       long nextReturnAttemptNanos) implements FlagState {
        }
    }

    public record StandLocation(String worldName, int x, int y, int z) {
    }

    private static final long DROP_AUTO_RETURN_DELAY_NANOS = 30_000_000_000L;
    private static final long DROP_RETRY_DELAY_NANOS = 5_000_000_000L;

    private final Object lock = new Object();

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final CtfStandRegistryRepository standRegistry;
    private final BiFunction<String, Integer, ItemStack> itemStackFactory;
    private final HytaleLogger logger;

    private final Map<CtfMatchService.Team, FlagState> stateByFlag = new EnumMap<>(CtfMatchService.Team.class);
    private final Map<String, CtfMatchService.Team> carrierFlagByUuid = new HashMap<>();
    private final Map<String, Byte> lockedSlotByUuid = new HashMap<>();

    public CtfFlagStateService(CtfMatchService matchService,
                               SimpleClaimsCtfBridge simpleClaims,
                               CtfStandRegistryRepository standRegistry,
                               BiFunction<String, Integer, ItemStack> itemStackFactory,
                               HytaleLogger logger) {
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.standRegistry = standRegistry;
        this.itemStackFactory = itemStackFactory;
        this.logger = logger;
        resetForNewMatch();
    }

    public void resetForNewMatch() {
        synchronized (lock) {
            stateByFlag.put(CtfMatchService.Team.RED, new FlagState.InStand(null, CtfMatchService.Team.RED.displayName()));
            stateByFlag.put(CtfMatchService.Team.BLUE, new FlagState.InStand(null, CtfMatchService.Team.BLUE.displayName()));
            stateByFlag.put(CtfMatchService.Team.YELLOW, new FlagState.InStand(null, CtfMatchService.Team.YELLOW.displayName()));
            stateByFlag.put(CtfMatchService.Team.WHITE, new FlagState.InStand(null, CtfMatchService.Team.WHITE.displayName()));
            carrierFlagByUuid.clear();
            lockedSlotByUuid.clear();
        }
    }

    public void onWithdrawn(String holderUuid,
                            @Nullable String holderName,
                            String flagItemId,
                            String worldName,
                            int x,
                            int y,
                            int z,
                            byte lockedHotbarSlot) {
        CtfMatchService.Team flag = flagTeamFromItemId(flagItemId);
        if (flag == null) return;

        synchronized (lock) {
            if (holderUuid != null && !holderUuid.isBlank()) {
                carrierFlagByUuid.put(holderUuid, flag);
                lockedSlotByUuid.put(holderUuid, lockedHotbarSlot);
            }
            stateByFlag.put(flag, new FlagState.Held(holderUuid, holderName, lockedHotbarSlot));
        }

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] flag withdrawn flag=%s holder=%s @ %s(%d,%d,%d) slot=%d",
                    flag.displayName(),
                    safeName(holderName, holderUuid),
                    worldName,
                    x, y, z,
                    lockedHotbarSlot);
        }
    }

    public void onWithdrawn(String holderUuid,
                            @Nullable String holderName,
                            String flagItemId,
                            String worldName,
                            int x,
                            int y,
                            int z) {
        onWithdrawn(holderUuid, holderName, flagItemId, worldName, x, y, z, (byte) 0);
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

        synchronized (lock) {
            if (depositorUuid != null && !depositorUuid.isBlank()) {
                CtfMatchService.Team carried = carrierFlagByUuid.get(depositorUuid);
                if (carried == flag) {
                    carrierFlagByUuid.remove(depositorUuid);
                    lockedSlotByUuid.remove(depositorUuid);
                }
            }

            FlagState previous = stateByFlag.get(flag);
            if (previous instanceof FlagState.Held held) {
                carrierFlagByUuid.remove(held.holderUuid());
                lockedSlotByUuid.remove(held.holderUuid());
            }

            stateByFlag.put(flag, new FlagState.InStand(new StandLocation(worldName, x, y, z), baseTeamName));
        }

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] flag deposited flag=%s base=%s by=%s @ %s(%d,%d,%d)",
                    flag.displayName(),
                    (baseTeamName == null) ? "<neutral>" : baseTeamName,
                    depositorUuid,
                    worldName,
                    x, y, z);
        }
    }

    public void assignFlagCarrier(CtfMatchService.Team flagTeam,
                                  String carrierUuid,
                                  @Nullable String carrierName,
                                  byte lockedHotbarSlot) {
        if (flagTeam == null || carrierUuid == null || carrierUuid.isBlank()) return;

        synchronized (lock) {
            carrierFlagByUuid.put(carrierUuid, flagTeam);
            lockedSlotByUuid.put(carrierUuid, lockedHotbarSlot);
            stateByFlag.put(flagTeam, new FlagState.Held(carrierUuid, carrierName, lockedHotbarSlot));
        }
    }

    public boolean markCarrierDropped(String carrierUuid,
                                      String worldName,
                                      double x,
                                      double y,
                                      double z) {
        if (carrierUuid == null || carrierUuid.isBlank()) return false;
        if (worldName == null || worldName.isBlank()) return false;

        synchronized (lock) {
            CtfMatchService.Team flagTeam = carrierFlagByUuid.remove(carrierUuid);
            lockedSlotByUuid.remove(carrierUuid);
            if (flagTeam == null) return false;

            long now = System.nanoTime();
            stateByFlag.put(flagTeam, new FlagState.Dropped(worldName, x, y, z, now, now + DROP_AUTO_RETURN_DELAY_NANOS));
            return true;
        }
    }

    public boolean isFlagDropped(CtfMatchService.Team flagTeam) {
        if (flagTeam == null) return false;
        synchronized (lock) {
            return stateByFlag.get(flagTeam) instanceof FlagState.Dropped;
        }
    }

    public boolean isCarryingAnyFlag(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        synchronized (lock) {
            return carrierFlagByUuid.containsKey(uuid);
        }
    }

    public @Nullable CtfMatchService.Team carriedFlagFor(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        synchronized (lock) {
            return carrierFlagByUuid.get(uuid);
        }
    }

    public @Nullable Byte lockedHotbarSlotForCarrier(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        synchronized (lock) {
            return lockedSlotByUuid.get(uuid);
        }
    }

    public void clearCarrierLock(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        synchronized (lock) {
            carrierFlagByUuid.remove(uuid);
            lockedSlotByUuid.remove(uuid);
        }
    }

    public @Nullable ItemStack removeOneFlagFromPlayer(Player player, CtfMatchService.Team flagTeam) {
        if (player == null || flagTeam == null) return null;
        Inventory inv = player.getInventory();
        if (inv == null) return null;

        String itemId = flagItemIdForTeam(flagTeam);
        if (itemId == null) return null;

        boolean removed = false;

        String playerUuid = (player.getUuid() == null) ? null : player.getUuid().toString();
        Byte lockedSlot = (playerUuid == null) ? null : lockedHotbarSlotForCarrier(playerUuid);

        if (lockedSlot != null) {
            ItemContainer hotbar = inv.getHotbar();
            if (hotbar != null) {
                short slot = (short) (lockedSlot & 0xFF);
                ItemStack stack = hotbar.getItemStack(slot);
                if (stack != null && itemId.equals(stack.getItemId())) {
                    hotbar.removeItemStackFromSlot(slot, 1);
                    removed = true;
                }
            }
        }

        if (!removed) {
            removed = removeOneFlagFromContainer(inv.getHotbar(), itemId)
                    || removeOneFlagFromContainer(inv.getStorage(), itemId)
                    || removeOneFlagFromContainer(inv.getBackpack(), itemId)
                    || removeOneFlagFromContainer(inv.getTools(), itemId)
                    || removeOneFlagFromContainer(inv.getUtility(), itemId)
                    || removeOneFlagFromContainer(inv.getArmor(), itemId);
        }

        if (!removed) {
            return createFlagStack(itemId);
        }

        try {
            player.sendInventory();
        } catch (Throwable ignored) {
        }

        return createFlagStack(itemId);
    }

    public boolean tryReturnDroppedFlagToHome(CtfMatchService.Team flagTeam, StandSwapService standSwapService) {
        if (flagTeam == null || standSwapService == null) return false;

        synchronized (lock) {
            if (!(stateByFlag.get(flagTeam) instanceof FlagState.Dropped)) {
                return false;
            }
        }

        CtfStandRegistryRepository.StandLocation destination = resolveValidHomeStand(flagTeam);
        if (destination == null) return false;

        World world = Universe.get().getWorld(destination.worldName());
        if (world == null) return false;

        String standId = standIdForTeam(flagTeam);
        if (standId == null) return false;

        if (!standSwapService.swapStand(world, destination.x(), destination.y(), destination.z(), standId)) {
            return false;
        }

        synchronized (lock) {
            stateByFlag.put(flagTeam,
                    new FlagState.InStand(
                            new StandLocation(destination.worldName(), destination.x(), destination.y(), destination.z()),
                            flagTeam.displayName()
                    ));
        }

        return true;
    }

    public void processDroppedFlagTimeouts(StandSwapService standSwapService) {
        if (standSwapService == null) return;
        long now = System.nanoTime();

        for (CtfMatchService.Team flagTeam : CtfMatchService.Team.values()) {
            FlagState.Dropped dropped;
            synchronized (lock) {
                FlagState current = stateByFlag.get(flagTeam);
                if (!(current instanceof FlagState.Dropped d)) continue;
                if (now < d.nextReturnAttemptNanos()) continue;
                dropped = d;
            }

            boolean returned = tryReturnDroppedFlagToHome(flagTeam, standSwapService);
            if (returned) continue;

            synchronized (lock) {
                FlagState current = stateByFlag.get(flagTeam);
                if (!(current instanceof FlagState.Dropped)) continue;
                stateByFlag.put(flagTeam, new FlagState.Dropped(
                        dropped.worldName(),
                        dropped.x(),
                        dropped.y(),
                        dropped.z(),
                        dropped.droppedAtNanos(),
                        now + DROP_RETRY_DELAY_NANOS
                ));
            }
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
        synchronized (lock) {
            for (CtfMatchService.Team flag : CtfMatchService.Team.values()) {
                FlagState st = stateByFlag.get(flag);
                if (st instanceof FlagState.InStand inStand) {
                    String base = inStand.baseTeamName();
                    if (base != null && !base.isBlank()) {
                        scores.merge(base, 1, Integer::sum);
                    }
                }
            }
        }
        return scores;
    }

    public void cleanupAfterMatch(StandSwapService standSwapService,
                                  Map<String, Player> playerByUuid,
                                  Iterable<String> matchUuids) {
        if (playerByUuid != null && matchUuids != null) {
            for (String uuid : matchUuids) {
                if (uuid == null || uuid.isBlank()) continue;
                Player player = playerByUuid.get(uuid);
                if (player == null) continue;
                removeAllFlagsFromPlayer(player);
            }
        }

        synchronized (lock) {
            if (standSwapService != null) {
                for (Map.Entry<CtfMatchService.Team, FlagState> entry : stateByFlag.entrySet()) {
                    CtfMatchService.Team flagTeam = entry.getKey();
                    FlagState state = entry.getValue();
                    if (!(state instanceof FlagState.InStand inStand)) continue;

                    StandLocation location = inStand.location();
                    if (location == null) continue;

                    String base = inStand.baseTeamName();
                    boolean isHome = (base != null) && base.equalsIgnoreCase(flagTeam.displayName());
                    if (isHome) continue;

                    World world = Universe.get().getWorld(location.worldName());
                    if (world == null) continue;
                    standSwapService.swapStand(world, location.x(), location.y(), location.z(), CtfRules.STAND_EMPTY);
                }
            }
            carrierFlagByUuid.clear();
            lockedSlotByUuid.clear();
        }
    }

    public static @Nullable CtfMatchService.Team flagTeamFromItemId(String flagItemId) {
        if (flagItemId == null) return null;
        return switch (flagItemId) {
            case CtfRules.FLAG_RED -> CtfMatchService.Team.RED;
            case CtfRules.FLAG_BLUE -> CtfMatchService.Team.BLUE;
            case CtfRules.FLAG_YELLOW -> CtfMatchService.Team.YELLOW;
            case CtfRules.FLAG_WHITE -> CtfMatchService.Team.WHITE;
            default -> null;
        };
    }

    public static @Nullable CtfMatchService.Team flagTeamFromStandId(String standId) {
        if (standId == null) return null;
        return switch (standId) {
            case CtfRules.STAND_RED -> CtfMatchService.Team.RED;
            case CtfRules.STAND_BLUE -> CtfMatchService.Team.BLUE;
            case CtfRules.STAND_YELLOW -> CtfMatchService.Team.YELLOW;
            case CtfRules.STAND_WHITE -> CtfMatchService.Team.WHITE;
            default -> null;
        };
    }

    public static @Nullable String flagItemIdForTeam(CtfMatchService.Team flagTeam) {
        if (flagTeam == null) return null;
        return switch (flagTeam) {
            case RED -> CtfRules.FLAG_RED;
            case BLUE -> CtfRules.FLAG_BLUE;
            case YELLOW -> CtfRules.FLAG_YELLOW;
            case WHITE -> CtfRules.FLAG_WHITE;
        };
    }

    public static @Nullable String standIdForTeam(CtfMatchService.Team flagTeam) {
        if (flagTeam == null) return null;
        return switch (flagTeam) {
            case RED -> CtfRules.STAND_RED;
            case BLUE -> CtfRules.STAND_BLUE;
            case YELLOW -> CtfRules.STAND_YELLOW;
            case WHITE -> CtfRules.STAND_WHITE;
        };
    }

    private CtfStandRegistryRepository.StandLocation resolveValidHomeStand(CtfMatchService.Team flagTeam) {
        if (standRegistry == null || flagTeam == null) return null;

        List<CtfStandRegistryRepository.StandLocation> candidates = standRegistry.getOrderedStands(flagTeam);
        if (candidates.isEmpty()) return null;

        for (CtfStandRegistryRepository.StandLocation candidate : candidates) {
            if (candidate == null || !candidate.isValid()) continue;

            World world = Universe.get().getWorld(candidate.worldName());
            if (world == null) continue;

            if (simpleClaims != null && simpleClaims.isAvailable()) {
                int chunkX = ChunkUtil.chunkCoordinate(candidate.x());
                int chunkZ = ChunkUtil.chunkCoordinate(candidate.z());
                String ownerTeam = simpleClaims.getTeamForChunk(candidate.worldName(), chunkX, chunkZ);
                if (ownerTeam == null || !ownerTeam.equalsIgnoreCase(flagTeam.displayName())) {
                    continue;
                }
            }

            return candidate;
        }

        return null;
    }

    private String formatForHud(CtfMatchService.Team flagTeam) {
        FlagState state;
        synchronized (lock) {
            state = stateByFlag.get(flagTeam);
        }

        if (state == null) return "Neutral";

        if (state instanceof FlagState.InStand inStand) {
            String base = inStand.baseTeamName();
            return (base == null || base.isBlank()) ? "Neutral" : base;
        }

        if (state instanceof FlagState.Dropped) {
            return "Dropped";
        }

        if (state instanceof FlagState.Held held) {
            String holderName = safeName(held.holderName(), held.holderUuid());
            CtfMatchService.Team holderTeam = (matchService == null) ? null : matchService.activeMatchTeamFor(held.holderUuid());
            if (holderTeam != null && holderTeam.displayName() != null && !holderTeam.displayName().isBlank()) {
                return holderTeam.displayName() + " - " + holderName;
            }
            return holderName;
        }

        return "Neutral";
    }

    private @Nullable ItemStack createFlagStack(String itemId) {
        if (itemStackFactory == null || itemId == null || itemId.isBlank()) return null;
        try {
            return itemStackFactory.apply(itemId, 1);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to create flag item stack. item=%s", itemId);
            return null;
        }
    }

    private static String safeName(@Nullable String name, String uuid) {
        if (name != null && !name.isBlank()) return name;
        if (uuid == null) return "<player>";
        String u = uuid.trim();
        if (u.isEmpty()) return "<player>";
        return (u.length() <= 8) ? u : u.substring(0, 8);
    }

    private static boolean removeOneFlagFromContainer(ItemContainer container, String itemId) {
        if (container == null || itemId == null || itemId.isBlank()) return false;

        final short[] foundSlot = new short[]{-1};
        container.forEach((slot, stack) -> {
            if (foundSlot[0] >= 0) return;
            if (stack == null) return;
            if (!itemId.equals(stack.getItemId())) return;
            foundSlot[0] = slot;
        });

        if (foundSlot[0] < 0) return false;
        container.removeItemStackFromSlot(foundSlot[0], 1);
        return true;
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
