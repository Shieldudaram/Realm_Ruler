package com.Chris__.realm_ruler.npc;

public interface NpcSpawnAdapter {

    record SpawnRequest(String arenaId,
                        String npcName,
                        String worldName,
                        double x,
                        double y,
                        double z,
                        float pitch,
                        float yaw,
                        float roll,
                        String requesterUuid) {
    }

    record NpcHandle(String entityUuid, String backendId) {
    }

    record SpawnResult(boolean success, NpcHandle handle, String error) {
        public static SpawnResult success(NpcHandle handle) {
            return new SpawnResult(true, handle, null);
        }

        public static SpawnResult failure(String error) {
            return new SpawnResult(false, null, error);
        }
    }

    String backendId();

    SpawnResult spawnCombatDummy(SpawnRequest request);

    boolean despawn(NpcHandle handle);

    boolean isAlive(NpcHandle handle);
}
