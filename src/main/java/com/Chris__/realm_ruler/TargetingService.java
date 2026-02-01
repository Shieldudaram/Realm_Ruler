package com.Chris__.Realm_Ruler;

import com.hypixel.hytale.logger.HytaleLogger;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

/**
 * TargetingService (TARGET)
 *
 * Purpose:
 * - Convert a PlayerInteractLib PlayerInteractionEvent ("WHO" + "WHAT") into a concrete world target ("WHERE"):
 *     world + x,y,z of the block the player intended to interact with.
 *
 * Why this exists:
 * - In the current Hytale build, PlayerInteractLib reliably reports the player and InteractionType,
 *   but does NOT always include the exact block position being targeted.
 * - Target resolution is the most brittle part of the plugin (likely to break with monthly API changes),
 *   so we isolate it here to keep the rest of the codebase stable.
 *
 * Strategy (layered resolution):
 * 1) CHAIN (preferred):
 *    - Attempt to extract a hit position from the interaction "chain" object, when available.
 * 2) LOOK fallback ("EyeSpy"):
 *    - If chain data is missing/insufficient, use per-tick look tracking joined by uuid:
 *      (uuid + interaction) + (uuid -> recently looked-at block) => (world,x,y,z)
 *
 * Output:
 * - Returns a TargetingResult containing:
 *   - loc: the resolved BlockLocation (world,x,y,z)
 *   - look: the LookTarget used for fallback (non-null only when look fallback was used)
 *
 * Important notes:
 * - This is currently a "bridge refactor": the service calls some helper methods still housed
 *   in Realm_Ruler (tryExtractBlockLocation, getFreshLookTarget) to avoid behavior changes.
 * - Next refactor step (recommended): move the required helper methods + look tracker storage
 *   fully into this service, and/or promote BlockLocation/LookTarget/TargetingResult to top-level
 *   model classes (to remove nested-type coupling).
 *
 * Tags:
 * - TARGET  = target resolution / look tracking / interaction chain parsing
 * - COMPAT  = version-sensitive glue that may change with server updates
 */

// NOTE: Uses PlayerInteractLib's PlayerInteractionEvent type (not Hytale's similarly-named event).



public final class TargetingService {

    private final HytaleLogger logger;
    private final Realm_Ruler plugin;

    public TargetingService(HytaleLogger logger, Realm_Ruler plugin) {
        this.logger = logger;
        this.plugin = plugin;
    }

    // TARGETING: one place that turns (uuid + event + chain) into a BlockLocation (+ optional look info)
    // NOTE: package-private on purpose so we don't expose Realm_Ruler's internal types publicly.
    Realm_Ruler.TargetingResult resolveTarget(String uuid, PlayerInteractionEvent event, Object chain) {
        Realm_Ruler.BlockLocation loc = null;
        Realm_Ruler.LookTarget look = null;

        try {
            // Primary: attempt to extract location from interaction chain (if available)
            loc = plugin.tryExtractBlockLocation(uuid, event, chain);

            // Fallback: if chain doesn't provide a usable position, use "what the player was looking at"
            if (loc == null) {
                look = plugin.getFreshLookTarget(uuid);
                if (look != null && look.world != null && look.basePos != null) {
                    loc = new Realm_Ruler.BlockLocation(look.world, look.basePos.x, look.basePos.y, look.basePos.z);
                }
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-PI] Failed while extracting block location");
        }

        return (loc == null) ? null : new Realm_Ruler.TargetingResult(loc, look);
    }
}
