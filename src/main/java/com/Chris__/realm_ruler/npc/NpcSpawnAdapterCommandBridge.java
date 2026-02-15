package com.Chris__.realm_ruler.npc;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class NpcSpawnAdapterCommandBridge implements NpcSpawnAdapter {

    private static final Query<EntityStore> NPC_QUERY = Query.and(NPCEntity.getComponentType());
    private static final List<String> ROLE_CANDIDATES = List.of(
            "villager",
            "human",
            "guard",
            "bandit",
            "farmer",
            "worker"
    );
    private static final List<String> SPAWN_COMMAND_SUFFIXES = List.of("", " 1", " 1 1");

    private final HytaleLogger logger;
    private final Object spawnTemplateLock = new Object();
    private String cachedSpawnCommandSuffix = null;

    public NpcSpawnAdapterCommandBridge(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String backendId() {
        return "command-bridge";
    }

    @Override
    public SpawnResult spawnCombatDummy(SpawnRequest request) {
        if (request == null) return SpawnResult.failure("invalid request");
        if (request.requesterUuid() == null || request.requesterUuid().isBlank()) {
            return SpawnResult.failure("requester required");
        }

        UUID requesterUuid;
        try {
            requesterUuid = UUID.fromString(request.requesterUuid());
        } catch (Throwable ignored) {
            return SpawnResult.failure("invalid requester uuid");
        }

        PlayerRef playerRef = Universe.get().getPlayer(requesterUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return SpawnResult.failure("requester not online");
        }

        World world = Universe.get().getWorld(request.worldName());
        if (world == null) {
            return SpawnResult.failure("world not found: " + request.worldName());
        }

        for (String role : ROLE_CANDIDATES) {
            for (String suffix : orderedSpawnCommandSuffixes()) {
                Map<UUID, Vector3d> before = collectNpcPositions(world);
                if (before == null) continue;

                String command = "npc spawn " + role + suffix;
                boolean executed = executeCommand(playerRef, command);
                if (!executed) continue;

                Map<UUID, Vector3d> after = collectNpcPositions(world);
                if (after == null) continue;

                UUID created = findBestNewNpc(before, after, request.x(), request.y(), request.z());
                if (created != null) {
                    rememberSuccessfulSpawnSuffix(suffix);
                    return SpawnResult.success(new NpcHandle(created.toString(), backendId()));
                }
            }
        }

        clearCachedSpawnSuffix();
        return SpawnResult.failure("npc command spawn failed");
    }

    @Override
    public boolean despawn(NpcHandle handle) {
        UUID uuid = parseUuid(handle);
        if (uuid == null) return false;

        for (World world : Universe.get().getWorlds().values()) {
            if (world == null) continue;
            Entity found = world.getEntity(uuid);
            if (found == null) continue;
            Boolean removed = runOnWorldThread(world, () -> {
                Entity live = world.getEntity(uuid);
                if (live == null) return false;
                live.remove();
                return true;
            });
            return Boolean.TRUE.equals(removed);
        }
        return false;
    }

    @Override
    public boolean isAlive(NpcHandle handle) {
        UUID uuid = parseUuid(handle);
        if (uuid == null) return false;

        for (World world : Universe.get().getWorlds().values()) {
            if (world == null) continue;
            Entity found = world.getEntity(uuid);
            if (found == null) continue;
            Boolean alive = runOnWorldThread(world, () -> {
                Entity live = world.getEntity(uuid);
                return live != null && !live.wasRemoved();
            });
            return Boolean.TRUE.equals(alive);
        }
        return false;
    }

    private boolean executeCommand(PlayerRef playerRef, String command) {
        try {
            CommandManager.get().handleCommand(playerRef, command).get(3, TimeUnit.SECONDS);
            return true;
        } catch (Throwable t) {
            logger.atInfo().log("[RR-NPC] NPC command backend attempt failed. command=%s", command);
            return false;
        }
    }

    private List<String> orderedSpawnCommandSuffixes() {
        List<String> ordered = new ArrayList<>(SPAWN_COMMAND_SUFFIXES.size());
        String cached;
        synchronized (spawnTemplateLock) {
            cached = cachedSpawnCommandSuffix;
        }
        if (cached != null && SPAWN_COMMAND_SUFFIXES.contains(cached)) {
            ordered.add(cached);
        }
        for (String suffix : SPAWN_COMMAND_SUFFIXES) {
            if (ordered.contains(suffix)) continue;
            ordered.add(suffix);
        }
        return ordered;
    }

    private void rememberSuccessfulSpawnSuffix(@Nullable String suffix) {
        synchronized (spawnTemplateLock) {
            cachedSpawnCommandSuffix = suffix;
        }
    }

    private void clearCachedSpawnSuffix() {
        synchronized (spawnTemplateLock) {
            cachedSpawnCommandSuffix = null;
        }
    }

    private UUID parseUuid(NpcHandle handle) {
        if (handle == null || handle.entityUuid() == null || handle.entityUuid().isBlank()) return null;
        try {
            return UUID.fromString(handle.entityUuid());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<UUID, Vector3d> collectNpcPositions(World world) {
        return runOnWorldThread(world, () -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) return null;

            Map<UUID, Vector3d> byUuid = new HashMap<>();
            store.forEachChunk(NPC_QUERY, (chunk, commandBuffer) -> {
                for (int index = 0; index < chunk.size(); index++) {
                    NPCEntity npc = chunk.getComponent(index, NPCEntity.getComponentType());
                    if (npc == null || npc.getUuid() == null || npc.wasRemoved()) continue;
                    TransformComponent transform = npc.getTransformComponent();
                    Vector3d pos = (transform == null) ? null : transform.getPosition();
                    byUuid.put(npc.getUuid(), pos);
                }
            });
            return byUuid;
        });
    }

    private static UUID findBestNewNpc(Map<UUID, Vector3d> before,
                                       Map<UUID, Vector3d> after,
                                       double x,
                                       double y,
                                       double z) {
        if (after == null || after.isEmpty()) return null;

        UUID bestUuid = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Map.Entry<UUID, Vector3d> entry : after.entrySet()) {
            UUID uuid = entry.getKey();
            if (uuid == null) continue;
            if (before != null && before.containsKey(uuid)) continue;

            Vector3d pos = entry.getValue();
            if (pos == null) return uuid;

            double dx = pos.getX() - x;
            double dy = pos.getY() - y;
            double dz = pos.getZ() - z;
            double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestUuid = uuid;
            }
        }
        return bestUuid;
    }

    private <T> T runOnWorldThread(World world, Supplier<T> action) {
        if (world == null || action == null) return null;
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store != null && store.isInThread()) {
                return action.get();
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-NPC] Failed world-thread check.");
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    future.complete(action.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(3, TimeUnit.SECONDS);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-NPC] World-thread execution failed.");
            return null;
        }
    }
}
