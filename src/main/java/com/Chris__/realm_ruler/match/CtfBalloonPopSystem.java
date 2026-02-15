package com.Chris__.realm_ruler.match;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nullable;

public final class CtfBalloonPopSystem extends DamageEventSystem {

    private record AttackerInfo(String uuid, @Nullable Player player) {
    }

    private final Query<EntityStore> query = Query.and(NPCEntity.getComponentType());
    private final CtfBalloonSpawnService balloonSpawnService;

    public CtfBalloonPopSystem(CtfBalloonSpawnService balloonSpawnService) {
        this.balloonSpawnService = balloonSpawnService;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(int entityId,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage event) {
        if (event == null || balloonSpawnService == null || chunk == null || commandBuffer == null) return;

        Ref<EntityStore> targetRef = chunk.getReferenceTo(entityId);
        if (targetRef == null || !targetRef.isValid()) return;
        if (!balloonSpawnService.consumeTrackedBalloon(targetRef)) return;

        event.setCancelled(true);
        commandBuffer.tryRemoveEntity(targetRef, com.hypixel.hytale.component.RemoveReason.REMOVE);

        AttackerInfo attacker = resolveAttacker(event, store, commandBuffer);
        balloonSpawnService.onBalloonPopped(
                attacker == null ? null : attacker.uuid(),
                attacker == null ? null : attacker.player()
        );
    }

    private @Nullable AttackerInfo resolveAttacker(Damage damage,
                                                   Store<EntityStore> store,
                                                   CommandBuffer<EntityStore> commandBuffer) {
        if (damage == null) return null;
        Damage.Source source = damage.getSource();
        if (source == null) return null;

        if (source instanceof Damage.EntitySource entitySource) {
            AttackerInfo direct = resolveAttackerFromRef(entitySource.getRef(), store, commandBuffer);
            if (direct != null) return direct;
        }

        if (source instanceof Damage.ProjectileSource projectileSource) {
            AttackerInfo projectile = resolveAttackerFromRef(projectileSource.getProjectile(), store, commandBuffer);
            if (projectile != null) return projectile;
        }

        return null;
    }

    private @Nullable AttackerInfo resolveAttackerFromRef(Ref<EntityStore> ref,
                                                          Store<EntityStore> store,
                                                          CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || !ref.isValid()) return null;

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null && store != null) {
            playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        }
        if (playerRef == null || playerRef.getUuid() == null) return null;

        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null && store != null) {
            player = store.getComponent(ref, Player.getComponentType());
        }

        return new AttackerInfo(playerRef.getUuid().toString(), player);
    }
}
