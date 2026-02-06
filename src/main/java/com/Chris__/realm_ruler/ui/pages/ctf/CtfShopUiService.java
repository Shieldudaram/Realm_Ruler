package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.match.CtfPointsRepository;
import com.Chris__.realm_ruler.match.CtfShopConfigRepository;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public final class CtfShopUiService {

    private final CtfPointsRepository pointsRepository;
    private final CtfShopConfigRepository shopConfigRepository;
    private final BiFunction<String, Integer, ItemStack> itemStackFactory;

    private final Set<String> pendingOpenByUuid = ConcurrentHashMap.newKeySet();

    public CtfShopUiService(CtfPointsRepository pointsRepository,
                            CtfShopConfigRepository shopConfigRepository,
                            BiFunction<String, Integer, ItemStack> itemStackFactory) {
        this.pointsRepository = pointsRepository;
        this.shopConfigRepository = shopConfigRepository;
        this.itemStackFactory = itemStackFactory;
    }

    public void requestOpen(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        pendingOpenByUuid.add(uuid);
    }

    public EntityTickingSystem<EntityStore> createSystem() {
        return new CtfShopUiSystem();
    }

    private final class CtfShopUiSystem extends EntityTickingSystem<EntityStore> {
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
                if (player == null || playerRef == null || playerRef.getUuid() == null) return;

                String uuid = playerRef.getUuid().toString();
                if (!pendingOpenByUuid.remove(uuid)) return;

                if (shopConfigRepository != null) {
                    // Reload on open so edits to ctf_shop.json apply without restarting the server.
                    shopConfigRepository.reload();
                }

                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store,
                        new CtfShopPage(playerRef, pointsRepository, shopConfigRepository, itemStackFactory));
            } catch (Throwable ignored) {
                // silent: per-tick system
            }
        }
    }
}

