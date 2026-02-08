package com.Chris__.realm_ruler.npc;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class NpcSpawnAdapterFallback implements NpcSpawnAdapter {

    private final HytaleLogger logger;

    public NpcSpawnAdapterFallback(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String backendId() {
        return "direct-world-spawn";
    }

    @Override
    public SpawnResult spawnCombatDummy(SpawnRequest request) {
        if (request == null) return SpawnResult.failure("invalid request");
        if (request.worldName() == null || request.worldName().isBlank()) {
            return SpawnResult.failure("missing world");
        }

        World world = Universe.get().getWorld(request.worldName());
        if (world == null) {
            return SpawnResult.failure("world not found: " + request.worldName());
        }

        NpcHandle handle = runOnWorldThread(world, () -> {
            NPCEntity npc = new NPCEntity(world);
            NPCEntity spawned = world.spawnEntity(
                    npc,
                    new Vector3d(request.x(), request.y(), request.z()),
                    new Vector3f(request.pitch(), request.yaw(), request.roll())
            );
            if (spawned == null || spawned.getUuid() == null) return null;
            return new NpcHandle(spawned.getUuid().toString(), backendId());
        });

        if (handle == null) {
            return SpawnResult.failure("spawn failed");
        }
        return SpawnResult.success(handle);
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

    private UUID parseUuid(NpcHandle handle) {
        if (handle == null || handle.entityUuid() == null || handle.entityUuid().isBlank()) return null;
        try {
            return UUID.fromString(handle.entityUuid());
        } catch (Throwable ignored) {
            return null;
        }
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
