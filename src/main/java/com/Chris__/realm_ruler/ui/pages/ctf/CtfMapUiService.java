package com.Chris__.realm_ruler.ui.pages.ctf;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class CtfMapUiService {

    private final Set<String> pendingOpenByUuid = ConcurrentHashMap.newKeySet();
    private final Supplier<Boolean> uiAvailableSupplier;

    public CtfMapUiService() {
        this(() -> true);
    }

    public CtfMapUiService(Supplier<Boolean> uiAvailableSupplier) {
        this.uiAvailableSupplier = (uiAvailableSupplier == null) ? () -> true : uiAvailableSupplier;
    }

    public boolean isUiAvailable() {
        try {
            return uiAvailableSupplier.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void requestOpen(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        pendingOpenByUuid.add(uuid);
    }

    public EntityTickingSystem<EntityStore> createSystem() {
        return new CtfMapUiSystem();
    }

    private final class CtfMapUiSystem extends EntityTickingSystem<EntityStore> {
        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

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
            try {
                Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);

                Player player = (Player) holder.getComponent(Player.getComponentType());
                PlayerRef playerRef = (PlayerRef) holder.getComponent(PlayerRef.getComponentType());
                if (player == null || playerRef == null) return;
                if (playerRef.getUuid() == null) return;

                String uuid = playerRef.getUuid().toString();
                if (!pendingOpenByUuid.remove(uuid)) return;

                if (!isUiAvailable()) {
                    player.sendMessage(Message.raw("[RealmRuler] CTF map UI is unavailable right now (missing UI assets)."));
                    return;
                }

                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store, new CtfMapPage(playerRef));
            } catch (Throwable ignored) {
                // silent: per-tick system
            }
        }
    }
}
