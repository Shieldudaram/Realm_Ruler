package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.util.SpawnTeleportUtil;
import com.Chris__.realm_ruler.world.StandSwapService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CtfAutoRespawnAndTeleportSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private static final double SPAWN_JITTER_RADIUS_BLOCKS = 3.0d;
    private static final long RESPAWN_DELAY_NANOS = 5_000_000_000L;

    private final CtfMatchService matchService;
    private final CtfFlagStateService flagStateService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final TargetingService targetingService;
    private final StandSwapService standSwapService;
    private final HytaleLogger logger;

    private boolean warnedMissingSimpleClaims = false;
    private final Map<String, PendingRespawn> pendingRespawnsByUuid = new ConcurrentHashMap<>();

    private static final class PendingRespawn {
        private final Ref<EntityStore> ref;
        private long readyAtNanos;
        private int lastAnnouncedSecond;

        private PendingRespawn(Ref<EntityStore> ref, long readyAtNanos) {
            this.ref = ref;
            this.readyAtNanos = readyAtNanos;
            this.lastAnnouncedSecond = 5;
        }
    }

    public CtfAutoRespawnAndTeleportSystem(CtfMatchService matchService,
                                           CtfFlagStateService flagStateService,
                                           SimpleClaimsCtfBridge simpleClaims,
                                           TargetingService targetingService,
                                           StandSwapService standSwapService,
                                           HytaleLogger logger) {
        this.matchService = matchService;
        this.flagStateService = flagStateService;
        this.simpleClaims = simpleClaims;
        this.targetingService = targetingService;
        this.standSwapService = standSwapService;
        this.logger = logger;
    }

    @Override
    public com.hypixel.hytale.component.ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), TransformComponent.getComponentType());
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref,
                                 DeathComponent death,
                                 Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || death == null || store == null || commandBuffer == null) return;
        if (matchService == null) return;
        if (!matchService.isRunning()) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = (PlayerRef) commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        }
        if (player == null || playerRef == null || playerRef.getUuid() == null) return;

        String uuidStr = playerRef.getUuid().toString();
        if (!matchService.isActiveMatchParticipant(uuidStr)) return;

        dropCarriedFlagOnDeath(ref, store, player, uuidStr);

        // Best-effort: suppress death menu + auto-respawn.
        try {
            death.setShowDeathMenu(false);
            commandBuffer.putComponent(ref, DeathComponent.getComponentType(), death);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR] Failed to update DeathComponent for auto-respawn. uuid=%s", uuidStr);
        }

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR] CTF death detected; scheduling auto-respawn. uuid=%s", uuidStr);
        }

        long readyAt = System.nanoTime() + RESPAWN_DELAY_NANOS;
        pendingRespawnsByUuid.put(uuidStr, new PendingRespawn(ref, readyAt));
        player.sendMessage(com.hypixel.hytale.server.core.Message.raw("[RealmRuler] Respawning in 5..."));
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref,
                               DeathComponent previous,
                               DeathComponent current,
                               Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
        // No-op. We only care about added (death) and removed (respawn complete).
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref,
                                   DeathComponent death,
                                   Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || store == null) return;
        if (matchService == null) return;
        if (!matchService.isRunning()) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = (PlayerRef) commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        }
        if (player == null || playerRef == null || playerRef.getUuid() == null) return;

        String uuidStr = playerRef.getUuid().toString();
        pendingRespawnsByUuid.remove(uuidStr);
        CtfMatchService.Team team = matchService.activeMatchTeamFor(uuidStr);
        if (team == null) return;

        if (simpleClaims == null || !simpleClaims.isAvailable()) {
            if (!warnedMissingSimpleClaims) {
                warnedMissingSimpleClaims = true;
                logger.atWarning().log("[RR] SimpleClaims not available; CTF respawn teleports disabled.");
            }
            return;
        }

        var spawn = simpleClaims.getTeamSpawn(team.displayName());
        if (spawn == null) {
            logger.atWarning().log("[RR] Missing team spawn on respawn. team=%s uuid=%s", team.displayName(), uuidStr);
            return;
        }

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR] CTF respawn complete; teleporting to team spawn. uuid=%s team=%s", uuidStr, team.displayName());
        }

        SpawnTeleportUtil.queueTeamSpawnTeleport(
                targetingService,
                uuidStr,
                spawn.world(),
                spawn.x(),
                spawn.y(),
                spawn.z(),
                SPAWN_JITTER_RADIUS_BLOCKS
        );
    }

    private void dropCarriedFlagOnDeath(Ref<EntityStore> ref,
                                        Store<EntityStore> store,
                                        Player player,
                                        String uuid) {
        if (ref == null || store == null || player == null || uuid == null || uuid.isBlank()) return;
        if (flagStateService == null) return;

        CtfMatchService.Team carriedFlag = flagStateService.carriedFlagFor(uuid);
        if (carriedFlag == null) return;

        // Policy: death recovery is reset-first, not drop-first.
        flagStateService.removeOneFlagFromPlayer(player, carriedFlag);

        boolean returnedNow = standSwapService != null && flagStateService.forceReturnFlagToStand(
                carriedFlag,
                standSwapService,
                CtfFlagStateService.ReturnResolutionMode.STRICT_THEN_SOFT
        );
        if (returnedNow) {
            if (RrDebugFlags.verbose()) {
                logger.atInfo().log("[RR-CTF] death flag recovery uuid=%s immediateReturn=true markedDropped=false", uuid);
            }
            return;
        }

        String worldName = null;
        double x = 0;
        double y = 0;
        double z = 0;
        boolean havePosition = false;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform != null && transform.getPosition() != null && player.getWorld() != null) {
            worldName = player.getWorld().getName();
            x = transform.getPosition().getX();
            y = transform.getPosition().getY();
            z = transform.getPosition().getZ();
            havePosition = (worldName != null && !worldName.isBlank());
        }

        if (!havePosition && targetingService != null) {
            TargetingService.PlayerLocationSnapshot snapshot = targetingService.getLatestPlayerLocation(uuid);
            if (snapshot != null && snapshot.isValid()) {
                worldName = snapshot.worldName();
                x = snapshot.x();
                y = snapshot.y();
                z = snapshot.z();
                havePosition = true;
            }
        }

        if (!havePosition) {
            CtfStandRegistryRepository.StandLocation home = flagStateService.resolveHomeStandLocation(carriedFlag);
            if (home != null && home.isValid()) {
                worldName = home.worldName();
                x = home.x();
                y = home.y();
                z = home.z();
                havePosition = true;
            }
        }

        boolean markedDropped = false;
        if (havePosition && worldName != null && !worldName.isBlank()) {
            markedDropped = flagStateService.markCarrierDropped(uuid, worldName, x, y, z);
            if (!markedDropped) {
                markedDropped = flagStateService.markFlagDropped(carriedFlag, uuid, worldName, x, y, z);
            }
        }

        if (!markedDropped) {
            CtfStandRegistryRepository.StandLocation home = flagStateService.resolveHomeStandLocation(carriedFlag);
            if (home != null && home.isValid()) {
                markedDropped = flagStateService.markFlagDropped(carriedFlag, uuid, home.worldName(), home.x(), home.y(), home.z());
            }
        }

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] death flag recovery uuid=%s immediateReturn=false markedDropped=%s",
                    uuid,
                    markedDropped);
        }

        if (!markedDropped) {
            logger.atWarning().log("[RR-CTF] Unable to recover flag after death. uuid=%s flag=%s immediateReturn=false markedDropped=false",
                    uuid,
                    carriedFlag.displayName());
        }
    }

    public void processPendingRespawns() {
        if (pendingRespawnsByUuid.isEmpty()) return;

        long now = System.nanoTime();
        for (Map.Entry<String, PendingRespawn> entry : pendingRespawnsByUuid.entrySet()) {
            String uuid = entry.getKey();
            PendingRespawn pending = entry.getValue();
            if (uuid == null || uuid.isBlank() || pending == null) {
                pendingRespawnsByUuid.remove(uuid, pending);
                continue;
            }

            Ref<EntityStore> ref = pending.ref;
            if (ref == null || !ref.isValid()) {
                pendingRespawnsByUuid.remove(uuid, pending);
                continue;
            }

            Store<EntityStore> store = ref.getStore();
            if (store == null) {
                pendingRespawnsByUuid.remove(uuid, pending);
                continue;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                pendingRespawnsByUuid.remove(uuid, pending);
                continue;
            }

            long remainingNanos = pending.readyAtNanos - now;
            if (remainingNanos > 0L) {
                int remainingSeconds = (int) Math.ceil(remainingNanos / 1_000_000_000d);
                if (remainingSeconds < pending.lastAnnouncedSecond && remainingSeconds > 0) {
                    pending.lastAnnouncedSecond = remainingSeconds;
                    player.sendMessage(com.hypixel.hytale.server.core.Message.raw("[RealmRuler] Respawning in " + remainingSeconds + "..."));
                }
                continue;
            }

            try {
                DeathComponent death = store.getComponent(ref, DeathComponent.getComponentType());
                if (death == null) {
                    pendingRespawnsByUuid.remove(uuid, pending);
                    continue;
                }

                DeathComponent.respawn(store, ref);
                try {
                    player.getPageManager().setPage(ref, store, Page.None);
                } catch (Throwable ignored) {
                }

                pendingRespawnsByUuid.remove(uuid, pending);
                if (RrDebugFlags.verbose()) {
                    logger.atInfo().log("[RR] CTF delayed respawn requested. uuid=%s", uuid);
                }
            } catch (Throwable t) {
                pending.readyAtNanos = now + 1_000_000_000L;
                logger.atWarning().withCause(t).log("[RR] CTF delayed respawn failed; retrying. uuid=%s", uuid);
            }
        }
    }

    public boolean isPendingRespawn(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        return pendingRespawnsByUuid.containsKey(uuid);
    }
}
