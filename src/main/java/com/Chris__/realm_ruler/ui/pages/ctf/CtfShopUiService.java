package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.match.CtfShopConfigRepository;
import com.Chris__.realm_ruler.match.CtfShopService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class CtfShopUiService {

    private final CtfShopConfigRepository shopConfigRepository;
    private final CtfShopService shopService;
    private final Supplier<Boolean> uiAvailableSupplier;
    private final HytaleLogger logger;

    private final Set<String> pendingOpenByUuid = ConcurrentHashMap.newKeySet();

    public CtfShopUiService(CtfShopConfigRepository shopConfigRepository,
                            CtfShopService shopService,
                            Supplier<Boolean> uiAvailableSupplier,
                            HytaleLogger logger) {
        this.shopConfigRepository = shopConfigRepository;
        this.shopService = shopService;
        this.uiAvailableSupplier = (uiAvailableSupplier == null) ? () -> true : uiAvailableSupplier;
        this.logger = logger;
    }

    public void requestOpen(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        pendingOpenByUuid.add(uuid);
    }

    public boolean isUiAvailable() {
        try {
            return uiAvailableSupplier.get();
        } catch (Throwable ignored) {
            return false;
        }
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

                if (!isUiAvailable()) {
                    player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                            "[RealmRuler] CTF shop UI is unavailable right now (missing UI assets)."
                    ));
                    return;
                }
                if (shopService == null) {
                    player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                            "[RealmRuler] CTF shop is not ready yet."
                    ));
                    return;
                }

                if (shopConfigRepository != null) {
                    // Reload on open so edits to ctf_shop.json apply without restarting the server.
                    shopConfigRepository.reload();
                }

                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store,
                        new CtfShopPage(playerRef, shopService));
            } catch (Throwable t) {
                if (logger != null) {
                    logger.atWarning().withCause(t).log("[RR-CTF] Failed to open CTF shop UI.");
                }
                try {
                    Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);
                    Player player = (Player) holder.getComponent(Player.getComponentType());
                    if (player != null) {
                        player.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                                "[RealmRuler] Failed to open CTF shop UI. Use /rr ctf shop list or /rr ctf shop buy <id>."
                        ));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
