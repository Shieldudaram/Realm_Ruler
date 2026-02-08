package com.Chris__.realm_ruler.npc;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

public final class NpcLifecycleSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private final NpcTestService npcTestService;

    public NpcLifecycleSystem(NpcTestService npcTestService) {
        this.npcTestService = npcTestService;
    }

    @Override
    public com.hypixel.hytale.component.ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(NPCEntity.getComponentType());
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref,
                                 DeathComponent death,
                                 Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        if (npcTestService == null || ref == null || store == null) return;
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) return;
        npcTestService.onNpcDeath(npc.getUuid().toString());
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref,
                               DeathComponent previous,
                               DeathComponent current,
                               Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref,
                                   DeathComponent death,
                                   Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
    }
}
