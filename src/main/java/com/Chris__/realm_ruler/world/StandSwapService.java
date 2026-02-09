package com.Chris__.realm_ruler.world;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StandSwapService  [WORLD]
 *
 * Responsibility:
 * - Perform stand block swaps in the world safely:
 *     - Validate the stand asset ID exists (string -> internal numeric ID)
 *     - Ensure the chunk is loaded before writing
 *     - Write the block change to the chunk
 *
 * Why this class exists:
 * - World writes should be centralized to reduce risk and simplify maintenance.
 * - The Hytale API can shift; keeping world-write code in one file limits "blast radius."
 *
 * Threading expectations:
 * - World writes should happen on the server tick thread.
 * - This service assumes it is being called from tick-safe code (or via your tick queue).
 *
 * Logging:
 * - Uses "warn-once" sets to avoid flooding logs for repeated missing assets/chunks.
 */
public final class StandSwapService {

    private final HytaleLogger logger;

    /**
     * Warn-once guard: asset keys that have already been warned about.
     * Prevents log spam if a configuration/asset mismatch repeats.
     */
    private final Set<String> warnedMissingStandKeys = ConcurrentHashMap.newKeySet();

    /**
     * Warn-once guard: chunk indices that have already been warned about.
     * Prevents log spam if many swaps target unloaded chunks.
     */
    private final Set<Long> warnedMissingChunkIndices = ConcurrentHashMap.newKeySet();

    public StandSwapService(HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Swap the block at (x,y,z) to the block type represented by standKey.
     *
     * Parameters:
     * - world: the active World containing the block
     * - x,y,z: block coordinates
     * - standKey: block asset id string (ex: "blocktypes/rr/stand_empty")
     *
     * Behavior:
     * - If the asset ID doesn't exist in the BlockType AssetMap, the swap is skipped.
     * - If the chunk isn't loaded, the swap is skipped.
     * - Otherwise the block is set in the chunk.
     *
     * Notes:
     * - This method intentionally does NOT load chunks. It only operates on loaded data.
     * - It also intentionally does NOT throw; it fails safely and logs warnings once.
     */
    public boolean swapStand(World world, int x, int y, int z, String standKey) {
        // Defensive: if we don't have a world or key, there is nothing to do.
        if (world == null || standKey == null) return false;

        /*
         * Convert standKey (string asset id) into the engine's internal numeric block ID.
         *
         * BlockType.getAssetMap().getIndex(key) behavior:
         * - Returns the internal block ID if present
         * - Returns Integer.MIN_VALUE if key is NOT present
         *
         * That MIN_VALUE sentinel is why we check explicitly here.
         */
        int newBlockId = BlockType.getAssetMap().getIndex(standKey);

        // getIndex returns Integer.MIN_VALUE when the key is not in the AssetMap.
        if (newBlockId == Integer.MIN_VALUE) {
            // Warn once per missing key to avoid log flooding.
            if (warnedMissingStandKeys.add(standKey)) {
                logger.atWarning().log("[RR] stand asset id not found for %s", standKey);
            }
            return false;
        }

        /*
         * Worlds are chunked. We must obtain the chunk that contains (x,z) before writing.
         * ChunkUtil.indexChunkFromBlock maps block coordinates -> chunk index.
         */
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk chunk = world.getChunk(chunkIndex);

        // If the chunk isn't loaded, we skip to avoid null pointer errors.
        // We also avoid forcing chunk loads here to keep behavior predictable.
        if (chunk == null) {
            // Warn once per missing chunk index to avoid log spam.
            if (warnedMissingChunkIndices.add(chunkIndex)) {
                logger.atWarning().log("[RR] chunk not loaded for swap at %d,%d,%d (chunkIndex=%d)",
                        x, y, z, chunkIndex);
            }
            return false;
        }

        /*
         * Final write:
         * - setBlock(x, y, z, blockId, meta)
         * - "0" is used as metadata here (keeps behavior simple for Phase 1).
         *
         * If you later introduce variants via metadata, this "0" becomes a parameter.
         */
        chunk.setBlock(x, y, z, newBlockId, 0);

        // Info log for visibility during development.
        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR] Swapped stand @ %d,%d,%d -> %s", x, y, z, standKey);
        }

        return true;
    }
}
