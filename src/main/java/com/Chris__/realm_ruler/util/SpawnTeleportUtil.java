package com.Chris__.realm_ruler.util;

import com.Chris__.realm_ruler.targeting.TargetingService;
import com.hypixel.hytale.math.util.ChunkUtil;

import java.util.concurrent.ThreadLocalRandom;

public final class SpawnTeleportUtil {

    private SpawnTeleportUtil() {
    }

    public static void queueTeamSpawnTeleport(TargetingService targetingService,
                                             String uuid,
                                             String world,
                                             double x,
                                             double y,
                                             double z,
                                             double jitterRadiusBlocks) {
        if (targetingService == null) return;
        if (uuid == null || uuid.isBlank()) return;
        if (world == null || world.isBlank()) return;

        int chunkX = ChunkUtil.chunkCoordinate(x);
        int chunkZ = ChunkUtil.chunkCoordinate(z);

        double minX = ChunkUtil.minBlock(chunkX) + 0.5d;
        double maxX = ChunkUtil.maxBlock(chunkX) + 0.5d;
        double minZ = ChunkUtil.minBlock(chunkZ) + 0.5d;
        double maxZ = ChunkUtil.maxBlock(chunkZ) + 0.5d;

        double x2 = x;
        double z2 = z;
        if (jitterRadiusBlocks > 0) {
            double jx = ThreadLocalRandom.current().nextDouble(-jitterRadiusBlocks, jitterRadiusBlocks);
            double jz = ThreadLocalRandom.current().nextDouble(-jitterRadiusBlocks, jitterRadiusBlocks);
            x2 = clamp(x + jx, minX, maxX);
            z2 = clamp(z + jz, minZ, maxZ);
        } else {
            x2 = clamp(x, minX, maxX);
            z2 = clamp(z, minZ, maxZ);
        }

        targetingService.queueTeleport(uuid, world, x2, y, z2);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
