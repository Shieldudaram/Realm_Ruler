package com.Chris__.Realm_Ruler;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WORLD: Centralized stand swapping logic (asset validation + chunk loaded + block write).
 * Keep world writes here so monthly Hytale changes are localized.
 */
public final class StandSwapService {

    private final HytaleLogger logger;

    // Warn once per missing key/chunk to avoid log flooding.
    private final Set<String> warnedMissingStandKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> warnedMissingChunkIndices = ConcurrentHashMap.newKeySet();

    public StandSwapService(HytaleLogger logger) {
        this.logger = logger;
    }

    public void swap(Realm_Ruler.BlockLocation loc, String standKey) {
        if (loc == null || loc.world == null) return;

        int newBlockId = BlockType.getAssetMap().getIndex(standKey);

        if (newBlockId == Integer.MIN_VALUE) {
            if (warnedMissingStandKeys.add(standKey)) {
                logger.atWarning().log("[RR] stand asset id not found for %s", standKey);
            }
            return;
        }

        long chunkIndex = ChunkUtil.indexChunkFromBlock(loc.x, loc.z);
        WorldChunk chunk = loc.world.getChunk(chunkIndex);

        if (chunk == null) {
            if (warnedMissingChunkIndices.add(chunkIndex)) {
                logger.atWarning().log("[RR] chunk not loaded for swap at %d,%d,%d (chunkIndex=%d)",
                        loc.x, loc.y, loc.z, chunkIndex);
            }
            return;
        }

        chunk.setBlock(loc.x, loc.y, loc.z, newBlockId, 0);
        logger.atInfo().log("[RR] Swapped stand @ %d,%d,%d -> %s", loc.x, loc.y, loc.z, standKey);
    }

}
