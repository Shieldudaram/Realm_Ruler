package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.util.SpawnTeleportUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class CtfAutoRespawnAndTeleportSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private static final double SPAWN_JITTER_RADIUS_BLOCKS = 3.0d;

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final TargetingService targetingService;
    private final HytaleLogger logger;

    private boolean warnedMissingSimpleClaims = false;

    public CtfAutoRespawnAndTeleportSystem(CtfMatchService matchService,
                                          SimpleClaimsCtfBridge simpleClaims,
                                          TargetingService targetingService,
                                          HytaleLogger logger) {
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.targetingService = targetingService;
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

        try {
            var currentWorld = player.getWorld();
            if (currentWorld == null) return;
            currentWorld.execute(() -> {
                try {
                    DeathComponent.respawn(store, ref);
                    if (RrDebugFlags.verbose()) {
                        logger.atInfo().log("[RR] CTF respawn requested. uuid=%s", uuidStr);
                    }
                } catch (Throwable t) {
                    logger.atWarning().withCause(t).log("[RR] CTF auto-respawn failed. uuid=%s", uuidStr);
                }
            });
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR] Failed to schedule CTF auto-respawn. uuid=%s", uuidStr);
        }
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
}
