package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class CtfMainUiService {

    private final CtfWorkflowFacade workflow;
    private final Supplier<Boolean> uiAvailableSupplier;
    private final HytaleLogger logger;

    private final Set<String> pendingOpenByUuid = ConcurrentHashMap.newKeySet();

    public CtfMainUiService(CtfWorkflowFacade workflow,
                            Supplier<Boolean> uiAvailableSupplier,
                            HytaleLogger logger) {
        this.workflow = workflow;
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
        return new CtfMainUiSystem();
    }

    private final class CtfMainUiSystem extends EntityTickingSystem<EntityStore> {
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
            String uuid = null;
            try {
                Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);

                Player player = (Player) holder.getComponent(Player.getComponentType());
                PlayerRef playerRef = (PlayerRef) holder.getComponent(PlayerRef.getComponentType());
                if (player == null || playerRef == null || playerRef.getUuid() == null) return;

                uuid = playerRef.getUuid().toString();
                if (!pendingOpenByUuid.remove(uuid)) return;

                if (!isUiAvailable()) {
                    player.sendMessage(Message.raw("[RealmRuler] CTF UI is unavailable right now (missing UI assets)."));
                    return;
                }
                if (workflow == null) {
                    player.sendMessage(Message.raw("[RealmRuler] CTF UI is not ready yet."));
                    return;
                }

                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store,
                        new CtfMainPage(playerRef, workflow));
            } catch (Throwable t) {
                if (logger != null) {
                    logger.atWarning().withCause(t).log("[RR-CTF] Failed to open the main CTF UI. uuid=%s", String.valueOf(uuid));
                }
                try {
                    Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);
                    Player player = (Player) holder.getComponent(Player.getComponentType());
                    if (player != null) {
                        player.sendMessage(Message.raw("[RealmRuler] Failed to open the main CTF UI. Try /ctf again."));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
