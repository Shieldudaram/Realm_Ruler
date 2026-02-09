package com.Chris__.realm_ruler.match;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class CtfCarrierDropBlockSystem extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

    private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

    private final CtfMatchService matchService;
    private final CtfFlagStateService flagStateService;

    public CtfCarrierDropBlockSystem(CtfMatchService matchService, CtfFlagStateService flagStateService) {
        super(DropItemEvent.PlayerRequest.class);
        this.matchService = matchService;
        this.flagStateService = flagStateService;
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
                       DropItemEvent.PlayerRequest event) {
        if (event == null || matchService == null || flagStateService == null) return;
        if (!matchService.isRunning()) return;
        if (event.getInventorySectionId() != Inventory.HOTBAR_SECTION_ID) return;

        Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getUuid() == null) return;

        String uuid = playerRef.getUuid().toString();
        if (!matchService.isActiveMatchParticipant(uuid)) return;

        Byte lockedSlot = flagStateService.lockedHotbarSlotForCarrier(uuid);
        if (lockedSlot == null) return;
        if (event.getSlotId() != (short) (lockedSlot & 0xFF)) return;

        event.setCancelled(true);
    }
}
