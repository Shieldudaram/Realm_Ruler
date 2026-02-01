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

/**
 * StandSwapService (WORLD)
 *
 * Purpose:
 * - Perform the actual "swap placed stand block variant" world edit in one centralized place.
 * - This service is the ONLY code path that should write stand blocks (chunk.setBlock) so that
 *   future safety fixes and API changes are localized to a single file.
 *
 * What it does:
 * 1) Validates the target stand asset key exists in the BlockType asset map.
 * 2) Ensures the containing chunk is loaded before attempting to write.
 * 3) Writes the new block ID into the world at (x,y,z).
 * 4) Uses warn-once guards to prevent log spam for missing assets/chunks.
 *
 * Why centralize world writes:
 * - PlayerInteractLib events may arrive off the main tick thread (async).
 * - World writes are sensitive and can cause race conditions or crashes if done unsafely.
 * - Centralizing writes makes it straightforward to later enforce tick-thread execution.
 *
 * Current threading behavior:
 * - Writes are performed inline (call-site thread) but behind one entry point.
 *
 * Planned upgrade:
 * - Add a tick-thread executor/queue and route ALL world writes through it:
 *     worldWriteQueue.enqueue(() -> standSwapService.swap(...))
 *
 * Tags:
 * - WORLD   = world read/write (must be tick-thread safe)
 * - COMPAT  = may change as Hytale world/chunk APIs evolve
 */
// NOTE: This service should remain the only place that calls chunk.setBlock for stand swaps.


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
