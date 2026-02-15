package com.Chris__.realm_ruler.match;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class CtfBalloonPickupGuardSystem extends EntityEventSystem<EntityStore, InteractivelyPickupItemEvent> {

    private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

    private final CtfMatchService matchService;

    public CtfBalloonPickupGuardSystem(CtfMatchService matchService) {
        super(InteractivelyPickupItemEvent.class);
        this.matchService = matchService;
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
                       InteractivelyPickupItemEvent event) {
        if (event == null || matchService == null || !matchService.isRunning()) return;

        ItemStack stack = event.getItemStack();
        if (stack == null || stack.getItemId() == null) return;
        if (!CtfBalloonSpawnService.BALLOON_ITEM_ID.equals(stack.getItemId())) return;

        Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getUuid() == null) return;

        String uuid = playerRef.getUuid().toString();
        if (matchService.isActiveMatchParticipant(uuid)) return;

        event.setCancelled(true);
    }
}
