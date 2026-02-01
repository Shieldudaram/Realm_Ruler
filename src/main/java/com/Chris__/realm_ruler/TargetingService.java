package com.Chris__.Realm_Ruler;

import com.hypixel.hytale.logger.HytaleLogger;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

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
