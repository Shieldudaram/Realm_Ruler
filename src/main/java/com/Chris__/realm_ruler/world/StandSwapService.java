package com.Chris__.Realm_Ruler.world;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WORLD: Responsible for safe stand block swaps (asset id validation + chunk loaded + write).
 * Keep all world writes centralized here so API drift only hits one file.
 */
public final class StandSwapService {

    private final HytaleLogger logger;

    // Warn-once guards to prevent log flooding.
    private final Set<String> warnedMissingStandKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> warnedMissingChunkIndices = ConcurrentHashMap.newKeySet();

    public StandSwapService(HytaleLogger logger) {
        this.logger = logger;
    }

    public void swapStand(World world, int x, int y, int z, String standKey) {
        if (world == null || standKey == null) return;

        // Convert the block asset ID string to the internal numeric ID used by the engine.
        int newBlockId = BlockType.getAssetMap().getIndex(standKey);

        // getIndex returns Integer.MIN_VALUE when the key is not in the AssetMap.
        if (newBlockId == Integer.MIN_VALUE) {
            if (warnedMissingStandKeys.add(standKey)) {
                logger.atWarning().log("[RR] stand asset id not found for %s", standKey);
            }
            return;
        }

        // Worlds are chunked. We must load the chunk containing this block before editing.
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk chunk = world.getChunk(chunkIndex);

        // If chunk isn't loaded, skip the swap to avoid null pointer errors.
        if (chunk == null) {
            if (warnedMissingChunkIndices.add(chunkIndex)) {
                logger.atWarning().log("[RR] chunk not loaded for swap at %d,%d,%d (chunkIndex=%d)",
                        x, y, z, chunkIndex);
            }
            return;
        }

        // The final write.
        chunk.setBlock(x, y, z, newBlockId, 0);

        logger.atInfo().log("[RR] Swapped stand @ %d,%d,%d -> %s", x, y, z, standKey);
    }
}
