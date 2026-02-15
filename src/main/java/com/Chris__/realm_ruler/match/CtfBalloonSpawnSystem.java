package com.Chris__.realm_ruler.match;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class CtfBalloonSpawnSystem extends EntityTickingSystem<EntityStore> {

    private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    private final CtfBalloonSpawnService balloonSpawnService;

    public CtfBalloonSpawnSystem(CtfBalloonSpawnService balloonSpawnService) {
        this.balloonSpawnService = balloonSpawnService;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt,
                     int entityId,
                     ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store,
                     CommandBuffer<EntityStore> commandBuffer) {
        if (balloonSpawnService == null || store == null || commandBuffer == null) return;

        Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        String fallbackRequesterUuid = (playerRef == null || playerRef.getUuid() == null)
                ? null
                : playerRef.getUuid().toString();

        balloonSpawnService.processSlice(store, commandBuffer, fallbackRequesterUuid);
    }
}
