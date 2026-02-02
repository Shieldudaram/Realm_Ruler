package com.Chris__.Realm_Ruler.targeting;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * TARGET: Shared data models for target resolution.
 *
 * Why this file exists:
 * - These types were previously nested inside Realm_Ruler, which made that file huge.
 * - Multiple systems need them (TargetingService + modes like CtfMode).
 * - Keeping them here prevents circular dependencies and keeps the "wiring" class thin.
 */
public final class TargetingModels {

    private TargetingModels() {}

    /**
     * A concrete block location in a specific world.
     *
     * Note:
     * - We intentionally REQUIRE World (no "worldless" constructor) because you want multiworld later.
     * - If we ever need a "coords only" type, we should introduce a separate type explicitly.
     */
    public static final class BlockLocation {
        public final World world;
        public final int x;
        public final int y;
        public final int z;

        public BlockLocation(World world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Immutable data snapshot of what a player is looking at at a moment in time.
     */
    public static final class LookTarget {
        public final World world;

        /** Raw raycast hit position (can be a filler part of a larger block structure). */
        public final Vector3i targetPos;

        /** Resolved base position (adjusted for filler blocks so we target the base block). */
        public final Vector3i basePos;

        /** Best-effort string ID for the block type at basePos (used for debug/safety checks). */
        public final String blockId;

        /** Timestamp used to enforce freshness (we only trust recent aim data). */
        public final long nanoTime;

        public LookTarget(World world, Vector3i targetPos, Vector3i basePos, String blockId, long nanoTime) {
            this.world = world;
            this.targetPos = targetPos;
            this.basePos = basePos;
            this.blockId = blockId;
            this.nanoTime = nanoTime;
        }
    }

    /**
     * Final resolved target plus optional look-tracker metadata (only present if look fallback was used).
     */
    public static final class TargetingResult {
        public final BlockLocation loc;
        public final LookTarget look;

        public TargetingResult(BlockLocation loc, LookTarget look) {
            this.loc = loc;
            this.look = look;
        }
    }
}
