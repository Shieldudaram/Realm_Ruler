package com.Chris__.realm_ruler.npc;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class NpcTestService {

    private static final long RESPAWN_DELAY_MILLIS = 5_000L;
    private static final long RESPAWN_RETRY_MILLIS = 5_000L;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    public record ServiceResult(boolean success, String message) {
    }

    public record SpawnTransform(String worldName, double x, double y, double z,
                                 float pitch, float yaw, float roll) {
    }

    private static final class TrackedNpc {
        private final String arenaId;
        private final String npcName;
        private final SpawnTransform spawn;
        private NpcSpawnAdapter.NpcHandle handle;
        private boolean alive;
        private long respawnAtMillis;

        private TrackedNpc(String arenaId,
                           String npcName,
                           SpawnTransform spawn,
                           NpcSpawnAdapter.NpcHandle handle) {
            this.arenaId = arenaId;
            this.npcName = npcName;
            this.spawn = spawn;
            this.handle = handle;
            this.alive = true;
            this.respawnAtMillis = 0L;
        }
    }

    private final Object lock = new Object();
    private final HytaleLogger logger;
    private final NpcArenaRepository arenaRepository;
    private final NpcSpawnAdapter commandBridgeAdapter;
    private final NpcSpawnAdapter fallbackAdapter;

    private final Map<String, TrackedNpc> trackedByKey = new HashMap<>();
    private final Map<String, String> keyByEntityUuid = new HashMap<>();

    private String selectedBackendId = null;

    public NpcTestService(NpcArenaRepository arenaRepository,
                          NpcSpawnAdapter commandBridgeAdapter,
                          NpcSpawnAdapter fallbackAdapter,
                          HytaleLogger logger) {
        this.arenaRepository = arenaRepository;
        this.commandBridgeAdapter = commandBridgeAdapter;
        this.fallbackAdapter = fallbackAdapter;
        this.logger = logger;
    }

    public static String normalizeNpcName(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!NAME_PATTERN.matcher(normalized).matches()) return null;
        return normalized;
    }

    public ServiceResult spawn(String arenaId,
                               String npcName,
                               String requesterUuid,
                               SpawnTransform transform) {
        String normalizedArenaId = NpcArenaRepository.normalizeId(arenaId);
        String normalizedNpcName = normalizeNpcName(npcName);
        if (normalizedArenaId == null) return new ServiceResult(false, "Invalid arena id.");
        if (normalizedNpcName == null) return new ServiceResult(false, "Invalid npc name.");
        if (transform == null) return new ServiceResult(false, "Missing spawn transform.");

        NpcArenaRepository.ArenaDefinition arena = arenaRepository.getArena(normalizedArenaId);
        if (arena == null) return new ServiceResult(false, "Arena not found: " + normalizedArenaId);
        if (!arena.hasBounds()) return new ServiceResult(false, "Arena bounds are not set. Use /rr npc arena pos1 and pos2.");
        if (!arena.contains(transform.worldName(), transform.x(), transform.y(), transform.z())) {
            return new ServiceResult(false, "Spawn position must be inside arena bounds.");
        }

        String key = key(normalizedArenaId, normalizedNpcName);
        synchronized (lock) {
            if (trackedByKey.containsKey(key)) {
                return new ServiceResult(false, "NPC already exists in this arena: " + normalizedNpcName);
            }
        }

        NpcSpawnAdapter.SpawnRequest request = new NpcSpawnAdapter.SpawnRequest(
                normalizedArenaId,
                normalizedNpcName,
                transform.worldName(),
                transform.x(),
                transform.y(),
                transform.z(),
                transform.pitch(),
                transform.yaw(),
                transform.roll(),
                requesterUuid
        );

        NpcSpawnAdapter.SpawnResult spawnResult = spawnWithSelectedBackend(request);
        if (!spawnResult.success() || spawnResult.handle() == null) {
            String error = (spawnResult.error() == null || spawnResult.error().isBlank())
                    ? "Spawn failed."
                    : "Spawn failed: " + spawnResult.error();
            return new ServiceResult(false, error);
        }

        synchronized (lock) {
            TrackedNpc tracked = new TrackedNpc(normalizedArenaId, normalizedNpcName, transform, spawnResult.handle());
            trackedByKey.put(key, tracked);
            if (spawnResult.handle().entityUuid() != null && !spawnResult.handle().entityUuid().isBlank()) {
                keyByEntityUuid.put(spawnResult.handle().entityUuid(), key);
            }
        }

        return new ServiceResult(true, "Spawned NPC '" + normalizedNpcName + "' in arena '" + normalizedArenaId + "'.");
    }

    public ServiceResult despawn(String arenaId, String npcName) {
        String normalizedArenaId = NpcArenaRepository.normalizeId(arenaId);
        String normalizedNpcName = normalizeNpcName(npcName);
        if (normalizedArenaId == null) return new ServiceResult(false, "Invalid arena id.");
        if (normalizedNpcName == null) return new ServiceResult(false, "Invalid npc name.");

        String key = key(normalizedArenaId, normalizedNpcName);
        TrackedNpc tracked;
        synchronized (lock) {
            tracked = trackedByKey.remove(key);
            if (tracked == null) {
                return new ServiceResult(false, "NPC not found in arena: " + normalizedNpcName);
            }
            if (tracked.handle != null && tracked.handle.entityUuid() != null) {
                keyByEntityUuid.remove(tracked.handle.entityUuid());
            }
        }

        despawnHandle(tracked.handle);
        return new ServiceResult(true, "Despawned NPC '" + normalizedNpcName + "'.");
    }

    public int clearAll() {
        List<TrackedNpc> removed;
        synchronized (lock) {
            removed = new ArrayList<>(trackedByKey.values());
            trackedByKey.clear();
            keyByEntityUuid.clear();
        }
        for (TrackedNpc tracked : removed) {
            despawnHandle(tracked.handle);
        }
        return removed.size();
    }

    public int clearArena(String arenaId) {
        String normalizedArenaId = NpcArenaRepository.normalizeId(arenaId);
        if (normalizedArenaId == null) return 0;

        List<TrackedNpc> removed = new ArrayList<>();
        synchronized (lock) {
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, TrackedNpc> entry : trackedByKey.entrySet()) {
                TrackedNpc tracked = entry.getValue();
                if (tracked == null || !normalizedArenaId.equals(tracked.arenaId)) continue;
                keysToRemove.add(entry.getKey());
                removed.add(tracked);
                if (tracked.handle != null && tracked.handle.entityUuid() != null) {
                    keyByEntityUuid.remove(tracked.handle.entityUuid());
                }
            }
            for (String key : keysToRemove) {
                trackedByKey.remove(key);
            }
        }

        for (TrackedNpc tracked : removed) {
            despawnHandle(tracked.handle);
        }
        return removed.size();
    }

    public int trackedCount() {
        synchronized (lock) {
            return trackedByKey.size();
        }
    }

    public int trackedCountForArena(String arenaId) {
        String normalizedArenaId = NpcArenaRepository.normalizeId(arenaId);
        if (normalizedArenaId == null) return 0;
        synchronized (lock) {
            int count = 0;
            for (TrackedNpc tracked : trackedByKey.values()) {
                if (tracked == null) continue;
                if (normalizedArenaId.equals(tracked.arenaId)) count++;
            }
            return count;
        }
    }

    public void onNpcDeath(String entityUuid) {
        if (entityUuid == null || entityUuid.isBlank()) return;

        synchronized (lock) {
            String key = keyByEntityUuid.remove(entityUuid);
            if (key == null) return;
            TrackedNpc tracked = trackedByKey.get(key);
            if (tracked == null) return;
            if (!tracked.alive) return;

            tracked.alive = false;
            tracked.respawnAtMillis = System.currentTimeMillis() + RESPAWN_DELAY_MILLIS;
            logger.atInfo().log("[RR-NPC] NPC died; respawn scheduled. arena=%s npc=%s", tracked.arenaId, tracked.npcName);
        }
    }

    public void processRespawns() {
        long now = System.currentTimeMillis();
        List<String> dueKeys = new ArrayList<>();

        synchronized (lock) {
            for (Map.Entry<String, TrackedNpc> entry : trackedByKey.entrySet()) {
                TrackedNpc tracked = entry.getValue();
                if (tracked == null) continue;

                if (tracked.alive) {
                    if (tracked.handle == null || !isHandleAlive(tracked.handle)) {
                        tracked.alive = false;
                        tracked.respawnAtMillis = now + RESPAWN_DELAY_MILLIS;
                        if (tracked.handle != null && tracked.handle.entityUuid() != null) {
                            keyByEntityUuid.remove(tracked.handle.entityUuid());
                        }
                    }
                }

                if (!tracked.alive && tracked.respawnAtMillis > 0 && now >= tracked.respawnAtMillis) {
                    dueKeys.add(entry.getKey());
                }
            }
        }

        for (String key : dueKeys) {
            TrackedNpc snapshot;
            synchronized (lock) {
                snapshot = trackedByKey.get(key);
                if (snapshot == null || snapshot.alive) continue;
            }

            NpcSpawnAdapter.SpawnRequest request = new NpcSpawnAdapter.SpawnRequest(
                    snapshot.arenaId,
                    snapshot.npcName,
                    snapshot.spawn.worldName(),
                    snapshot.spawn.x(),
                    snapshot.spawn.y(),
                    snapshot.spawn.z(),
                    snapshot.spawn.pitch(),
                    snapshot.spawn.yaw(),
                    snapshot.spawn.roll(),
                    null
            );

            NpcSpawnAdapter.SpawnResult result = spawnWithSelectedBackend(request);
            synchronized (lock) {
                TrackedNpc live = trackedByKey.get(key);
                if (live == null || live.alive) continue;

                if (result.success() && result.handle() != null) {
                    live.handle = result.handle();
                    live.alive = true;
                    live.respawnAtMillis = 0L;
                    if (live.handle.entityUuid() != null && !live.handle.entityUuid().isBlank()) {
                        keyByEntityUuid.put(live.handle.entityUuid(), key);
                    }
                    logger.atInfo().log("[RR-NPC] NPC respawned. arena=%s npc=%s", live.arenaId, live.npcName);
                } else {
                    live.respawnAtMillis = System.currentTimeMillis() + RESPAWN_RETRY_MILLIS;
                    logger.atWarning().log("[RR-NPC] NPC respawn failed; retrying. arena=%s npc=%s reason=%s",
                            live.arenaId, live.npcName, result.error());
                }
            }
        }
    }

    private NpcSpawnAdapter.SpawnResult spawnWithSelectedBackend(NpcSpawnAdapter.SpawnRequest request) {
        String selected;
        synchronized (lock) {
            selected = selectedBackendId;
        }

        if (selected != null) {
            NpcSpawnAdapter adapter = adapterById(selected);
            if (adapter != null && canUseAdapterForRequest(adapter, request)) {
                NpcSpawnAdapter.SpawnResult result = adapter.spawnCombatDummy(request);
                if (result.success()) return result;
            }

            NpcSpawnAdapter fallbackResultAdapter = fallbackAdapter;
            if (fallbackResultAdapter != null && canUseAdapterForRequest(fallbackResultAdapter, request)) {
                return fallbackResultAdapter.spawnCombatDummy(request);
            }
            return NpcSpawnAdapter.SpawnResult.failure("all spawn backends failed");
        }

        if (commandBridgeAdapter != null && canUseAdapterForRequest(commandBridgeAdapter, request)) {
            NpcSpawnAdapter.SpawnResult commandResult = commandBridgeAdapter.spawnCombatDummy(request);
            if (commandResult.success()) {
                selectBackend(commandBridgeAdapter.backendId());
                return commandResult;
            }
        }

        if (fallbackAdapter != null && canUseAdapterForRequest(fallbackAdapter, request)) {
            NpcSpawnAdapter.SpawnResult fallbackResult = fallbackAdapter.spawnCombatDummy(request);
            if (fallbackResult.success()) {
                selectBackend(fallbackAdapter.backendId());
            }
            return fallbackResult;
        }

        return NpcSpawnAdapter.SpawnResult.failure("no spawn backend available");
    }

    private void selectBackend(String backendId) {
        if (backendId == null || backendId.isBlank()) return;
        synchronized (lock) {
            if (selectedBackendId != null) return;
            selectedBackendId = backendId;
        }
        logger.atInfo().log("[RR-NPC] Selected NPC spawn backend: %s", backendId);
    }

    private boolean canUseAdapterForRequest(NpcSpawnAdapter adapter, NpcSpawnAdapter.SpawnRequest request) {
        if (adapter == null || request == null) return false;
        if ("command-bridge".equals(adapter.backendId())) {
            return request.requesterUuid() != null && !request.requesterUuid().isBlank();
        }
        return true;
    }

    private NpcSpawnAdapter adapterById(String backendId) {
        if (backendId == null) return null;
        if (commandBridgeAdapter != null && backendId.equals(commandBridgeAdapter.backendId())) {
            return commandBridgeAdapter;
        }
        if (fallbackAdapter != null && backendId.equals(fallbackAdapter.backendId())) {
            return fallbackAdapter;
        }
        return null;
    }

    private boolean despawnHandle(NpcSpawnAdapter.NpcHandle handle) {
        if (handle == null) return false;
        NpcSpawnAdapter adapter = adapterById(handle.backendId());
        if (adapter != null && adapter.despawn(handle)) return true;
        if (fallbackAdapter != null && fallbackAdapter != adapter) {
            return fallbackAdapter.despawn(handle);
        }
        return false;
    }

    private boolean isHandleAlive(NpcSpawnAdapter.NpcHandle handle) {
        if (handle == null) return false;
        NpcSpawnAdapter adapter = adapterById(handle.backendId());
        if (adapter != null) return adapter.isAlive(handle);
        return fallbackAdapter != null && fallbackAdapter.isAlive(handle);
    }

    private static String key(String arenaId, String npcName) {
        return arenaId + ":" + npcName;
    }
}
