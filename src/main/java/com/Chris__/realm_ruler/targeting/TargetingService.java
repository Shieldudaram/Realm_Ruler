package com.Chris__.Realm_Ruler.targeting;

import com.Chris__.Realm_Ruler.ui.GlobalMatchTimerService;
import com.Chris__.Realm_Ruler.ui.TimerAction;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.Chris__.Realm_Ruler.targeting.TargetingModels.*;

// ============================================================================
// TARGETING: "WHERE did the interaction happen?"
// Single source of truth:
// - resolveTarget(...) calls tryExtractBlockLocation(...)
// - tryExtractBlockLocation(...) uses chain -> lookTracker -> UseBlock fallback
// - extractPosRecursive(...) is the reflection spelunker
// ============================================================================


/**
 * TARGET: Resolve WHERE an interaction happened.
 *
 * Layered strategy (keep behavior):
 * 1) CHAIN: try to spelunk interaction chain for x/y/z (+ optional world)
 * 2) LOOK: fallback to per-tick raycast aim snapshot (freshness window)
 * 3) USEBLOCK: fallback to last remembered UseBlock target (best-effort)
 *
 * This class is intentionally "engine-brittle glue" so Realm_Ruler stays small.
 */
public final class TargetingService {

    // --- GLOBAL MATCH TIMER (shared for everyone) ---
    private final GlobalMatchTimerService matchTimer = new GlobalMatchTimerService();
    private final ConcurrentLinkedQueue<TimerAction> timerActions = new ConcurrentLinkedQueue<>();

    // This makes sure the timer "ticks" only once per real time slice, even though tick(...) runs per player.
    private final AtomicLong nextGlobalTimerUpdateNanos = new AtomicLong(0L);
    private final AtomicLong lastGlobalTimerUpdateNanos = new AtomicLong(0L);


    // -------------------------------------------------------------------------
// EyeSpy-style looked-at block tracker (UUID -> what block they are aiming at)
// -------------------------------------------------------------------------

    /** How far the raycast should check for a targeted block. */
    private static final double LOOK_RAYCAST_RANGE = 5.0d;

    /** Freshness window: we only trust looked-at results captured very recently. */
    private static final long LOOK_FRESH_NANOS = 250_000_000L; // 250ms

    /** Latest looked-at block info per player UUID (string form). */
    private final Map<String, TargetingModels.LookTarget> lookByUuid = new ConcurrentHashMap<>();


    private final HytaleLogger logger;

    // TICK EXECUTOR seam: we keep draining the same queue as before (no behavior change).
    private final ConcurrentLinkedQueue<Runnable> tickQueue;

    // PLAYER CACHE seam: keep updating same map as before (uuid -> Player).
    private final Map<String, Player> playerByUuid;


    // -------------------------------------------------------------------------
    // UseBlock fallback remembered location
    // -------------------------------------------------------------------------

    private volatile BlockLocation pendingStandLocation = null;

    // -------------------------------------------------------------------------
    // Reflection dump guard (dev discovery aid)
    // -------------------------------------------------------------------------

    private final Set<Class<?>> dumpedInteractionClasses = ConcurrentHashMap.newKeySet();

    public void queueTimerStart(int seconds) {
        timerActions.add(new TimerAction.Start(seconds));
    }

    public void queueTimerStop() {
        timerActions.add(new TimerAction.Stop());
    }

    private void tickGlobalMatchTimerOncePerSlice() {
        long now = System.nanoTime();

        // Only allow one "winner" to tick the global timer about every 50ms.
        // If 10 players call tick(), only the first one per slice runs this body.
        long next = nextGlobalTimerUpdateNanos.get();
        if (now < next) return;
        if (!nextGlobalTimerUpdateNanos.compareAndSet(next, now + 50_000_000L)) return; // 50ms

        long last = lastGlobalTimerUpdateNanos.getAndSet(now);
        float dt = (last == 0L) ? 0f : (float) ((now - last) / 1_000_000_000.0);

        // Apply queued actions on the tick thread
        TimerAction a;
        while ((a = timerActions.poll()) != null) {
            if (a instanceof TimerAction.Start s) matchTimer.start(s.seconds());
            else if (a instanceof TimerAction.Stop) matchTimer.stop();
        }

        if (dt > 0f) {
            matchTimer.tick(dt);
        }
    }


    public TargetingService(HytaleLogger logger,
                            ConcurrentLinkedQueue<Runnable> tickQueue,
                            Map<String, Player> playerByUuid) {
        this.logger = logger;
        this.tickQueue = tickQueue;
        this.playerByUuid = playerByUuid;
    }



    // -------------------------------------------------------------------------
    // Public API used by modes / plugin
    // -------------------------------------------------------------------------

    public TargetingResult resolveTarget(String uuid, PlayerInteractionEvent event, Object chain) {
        BlockLocation loc = null;
        LookTarget look = null;

        try {
            loc = tryExtractBlockLocation(uuid, event, chain);

            if (loc == null) {
                look = getFreshLookTarget(uuid);
                if (look != null && look.world != null && look.basePos != null) {
                    loc = new BlockLocation(look.world, look.basePos.x, look.basePos.y, look.basePos.z);
                }
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-TARGET] resolveTarget failed");
        }

        return (loc == null) ? null : new TargetingResult(loc, look);
    }

    /** Hook for Realm_Ruler's UseBlock fallback to remember a location (optional telemetry). */
    public void rememberPendingStandLocation(BlockLocation loc) {
        this.pendingStandLocation = loc;
    }

    /** Create the per-tick system that raycasts player aim and updates lookByUuid + playerByUuid + drains tickQueue. */
    public EntityTickingSystem<EntityStore> createLookTargetTrackerSystem() {
        return new LookTargetTrackerSystem();
    }
    // -------------------------------------------------------------------------
    // Look tracker helpers
    // -------------------------------------------------------------------------

    public LookTarget getFreshLookTarget(String uuid) {
        if (uuid == null || uuid.isEmpty() || "<null>".equals(uuid)) return null;

        LookTarget t = lookByUuid.get(uuid);
        if (t == null) return null;

        long age = System.nanoTime() - t.nanoTime;
        if (age > LOOK_FRESH_NANOS) return null;

        return t;
    }

    private BlockLocation tryLocationFromLook(String uuid) {
        LookTarget t = getFreshLookTarget(uuid);
        if (t == null || t.world == null || t.basePos == null) return null;
        return new BlockLocation(t.world, t.basePos.x, t.basePos.y, t.basePos.z);
    }

    /**
     * TARGET RESOLUTION PIPELINE (single source of truth)
     *
     * Goal:
     *   Convert (player uuid + PlayerInteractLib event + "chain" object) into a concrete block location:
     *   (World + x,y,z).
     *
     * Why this exists:
     *   PlayerInteractLib reliably tells us WHO + WHAT (interaction type, etc),
     *   but not always WHERE (block position).
     *
     * Strategy order (most reliable -> least):
     *   1) Walk the interaction "chain" (reflection spelunker) and try to find x/y/z (+ maybe world)
     *   2) If chain fails, use our per-tick "look tracker" (uuid -> freshest aimed-at block)
     *   3) If still nothing, use last remembered UseBlockEvent location (rare fallback)
     *
     * Notes for future edits:
     *   - Keep this function small: orchestration only. Heavy lifting stays in extractPosRecursive.
     *   - Never add world writes or inventory mutations here. This should remain pure target resolution.
     */
    private BlockLocation tryExtractBlockLocation(String uuid, PlayerInteractionEvent event, Object chain) {
        World world = null;

        // 0) Try to get World directly from the event (some builds expose it).
        try {
            Object w = safeCall(event, "getWorld", "world");
            if (w instanceof World ww) world = ww;
        } catch (Throwable ignored) {}

        // 1) Primary: spelunk the chain for coordinates (and maybe a World).
        BlockLocation found = extractPosRecursive(chain, world, 0, "chain");
        if (found != null) return found;

        // 2) Secondary: look tracker (fresh within window).
        BlockLocation lookLoc = tryLocationFromLook(uuid);
        if (lookLoc != null) return lookLoc;

        // 3) Last resort: remembered UseBlock location.
        if (pendingStandLocation != null) {
            if (pendingStandLocation.world != null) return pendingStandLocation;
            if (world != null) return new BlockLocation(world, pendingStandLocation.x, pendingStandLocation.y, pendingStandLocation.z);
        }

        return null;
    }

    /**
     * REFLECTION SPELUNKER: recursively search an arbitrary object graph for a "position-like" object.
     *
     * How it works:
     *   - First tries to read x/y/z directly from obj (tryReadXYZ)
     *   - Then recursively explores members (methods + fields) whose names look "position-ish"
     *   - Carries forward a World if discovered anywhere in the graph
     *
     * Safety rails:
     *   - Depth limit prevents runaway recursion and massive graph walks
     *   - Keyword filter limits which members are explored
     *   - Skips primitive-ish values (String, Number, enums) to reduce useless recursion
     *
     * Debugging:
     *   - dumpInteractionSurface(obj) runs once per class (dumpedInteractionClasses guard)
     *   - Keep dumps gated behind a debug flag inside dumpInteractionSurface to avoid log explosions
     */
    private BlockLocation extractPosRecursive(Object obj, World world, int depth, String path) {
        if (obj == null) return null;

        // Safety rail: keep recursion bounded.
        if (depth > 4) return null;

        // 1) Does THIS object directly expose x/y/z (or blockX/blockY/blockZ)?
        BlockLocation direct = tryReadXYZ(obj, world);
        if (direct != null) {
            logger.atInfo().log("[RR-TARGET] FOUND pos via %s (%s)", path, obj.getClass().getName());
            return direct;
        }

        Class<?> cls = obj.getClass();

        // 2) Optional: dump class surface once (helps discover new API shapes after updates).
        if (dumpedInteractionClasses.add(cls)) {
            dumpInteractionSurface(obj); // should be gated by a DEBUG flag inside this method
        }

        // 3) Only recurse into members that *sound* related to targeting/position data.
        //    This keeps the search focused and fast.
        final String[] keys = new String[] {
                "hit", "target", "block", "pos", "position", "location", "coord", "world", "chunk"
        };

        // 4) Methods first (often more stable than fields).
        for (Method m : cls.getMethods()) {
            try {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == Void.TYPE) continue;

                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(name, keys)) continue;

                Object child = m.invoke(obj);
                if (child == null) continue;

                // Skip leafy values
                if (child instanceof String) continue;
                if (child instanceof Number) continue;
                if (child.getClass().isEnum()) continue;
                if (child.getClass().isPrimitive()) continue;

                // If we discover a World anywhere, carry it forward.
                if (world == null && child instanceof World ww) world = ww;

                BlockLocation res = extractPosRecursive(child, world, depth + 1, path + "." + m.getName() + "()");
                if (res != null) return res;

            } catch (Throwable ignored) {}
        }

        // 5) Then fields (less stable, but sometimes needed).
        for (Field f : cls.getDeclaredFields()) {
            try {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(name, keys)) continue;

                f.setAccessible(true);
                Object child = f.get(obj);
                if (child == null) continue;

                if (child instanceof String) continue;
                if (child instanceof Number) continue;
                if (child.getClass().isEnum()) continue;
                if (child.getClass().isPrimitive()) continue;

                if (world == null && child instanceof World ww) world = ww;

                BlockLocation res = extractPosRecursive(child, world, depth + 1, path + "." + f.getName());
                if (res != null) return res;

            } catch (Throwable ignored) {}
        }

        return null;
    }


    private BlockLocation tryReadXYZ(Object obj, World world) {
        if (obj == null) return null;

        Integer x = safeReadInt(obj, "x", "blockX", "getX", "getBlockX");
        Integer y = safeReadInt(obj, "y", "blockY", "getY", "getBlockY");
        Integer z = safeReadInt(obj, "z", "blockZ", "getZ", "getBlockZ");

        if (x != null && y != null && z != null) {
            // Try to discover world from obj if not provided
            if (world == null) {
                try {
                    Object w = safeCall(obj, "getWorld", "world");
                    if (w instanceof World ww) world = ww;
                } catch (Throwable ignored) {}
            }
            if (world != null) return new BlockLocation(world, x, y, z);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // LookTargetTrackerSystem (moved out of Realm_Ruler)
    // -------------------------------------------------------------------------

    private final class LookTargetTrackerSystem extends EntityTickingSystem<EntityStore> {
        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void tick(float dt,
                         int entityId,
                         ArchetypeChunk<EntityStore> chunk,
                         Store<EntityStore> store,
                         CommandBuffer<EntityStore> commandBuffer) {
            try {
                Holder<EntityStore> holder = EntityUtils.toHolder(entityId, chunk);

                Player player = (Player) holder.getComponent(Player.getComponentType());
                PlayerRef playerRef = (PlayerRef) holder.getComponent(PlayerRef.getComponentType());
                if (player == null || playerRef == null) return;

                String uuid = safeUuidFromPlayer(playerRef, player);
                if (uuid == null || uuid.isEmpty()) return;

                // Keep existing behavior: refresh player cache every tick
                playerByUuid.put(uuid, player);
                tickGlobalMatchTimerOncePerSlice();          // ticks the shared timer (only once per slice)
                matchTimer.renderForPlayer(uuid, player, playerRef); // shows the same time to everyone

                Vector3i hit = TargetUtil.getTargetBlock(chunk.getReferenceTo(entityId), LOOK_RAYCAST_RANGE, store);
                if (hit == null) return;

                EntityStore es = (EntityStore) store.getExternalData();
                if (es == null) return;

                World world = es.getWorld();
                if (world == null) return;

                long hitChunkIndex = ChunkUtil.indexChunkFromBlock(hit.x, hit.z);
                WorldChunk hitChunk = world.getChunkIfLoaded(hitChunkIndex);
                if (hitChunk == null) return;

                Vector3i base = resolveBaseBlock(hitChunk, hit.x, hit.y, hit.z);
                if (base == null) return;

                long baseChunkIndex = ChunkUtil.indexChunkFromBlock(base.x, base.z);
                WorldChunk baseChunk = (baseChunkIndex == hitChunkIndex)
                        ? hitChunk
                        : world.getChunkIfLoaded(baseChunkIndex);

                if (baseChunk == null) return;

                BlockType bt = baseChunk.getBlockType(base.x, base.y, base.z);
                String blockId = (bt == null) ? null : safeBlockTypeId(bt);

                lookByUuid.put(uuid, new LookTarget(world, hit, base, blockId, System.nanoTime()));

                // Keep existing behavior: drain tickQueue on tick thread here
                Runnable r;
                while ((r = tickQueue.poll()) != null) {
                    try { r.run(); }
                    catch (Throwable t) { logger.atWarning().withCause(t).log("[RR] tickQueue task failed"); }
                }

            } catch (Throwable ignored) {
                // silent: per-tick system
            }
        }

    }

    private static Vector3i resolveBaseBlock(WorldChunk chunk, int x, int y, int z) {
        int filler = chunk.getFiller(x, y, z);
        if (filler == 0) return new Vector3i(x, y, z);

        return new Vector3i(
                x - FillerBlockUtil.unpackX(filler),
                y - FillerBlockUtil.unpackY(filler),
                z - FillerBlockUtil.unpackZ(filler)
        );
    }

    // -------------------------------------------------------------------------
    // UUID extraction helpers
    // -------------------------------------------------------------------------

    private String safeUuidFromPlayer(PlayerRef ref, Player player) {
        String u = safeUuidFromObject(ref);
        if (u != null) return u;

        u = safeUuidFromObject(player);
        if (u != null) return u;

        try {
            String s = String.valueOf(ref);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
                    .matcher(s);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}

        return null;
    }

    private String safeUuidFromObject(Object obj) {
        if (obj == null) return null;

        String[] methods = new String[]{
                "getUuid", "uuid",
                "getPlayerUuid", "playerUuid",
                "getUniqueId", "uniqueId",
                "getId", "id"
        };

        for (String mName : methods) {
            try {
                Method m = obj.getClass().getMethod(mName);
                if (m.getParameterCount() != 0) continue;
                Object v = m.invoke(obj);
                if (v == null) continue;
                String s = String.valueOf(v);
                if (s.length() >= 32) return s;
            } catch (Throwable ignored) {}
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Small reflection utilities
    // -------------------------------------------------------------------------

    private static Object safeCall(Object obj, String... methodNames) {
        if (obj == null || methodNames == null) return null;

        for (String name : methodNames) {
            try {
                Method m = obj.getClass().getMethod(name);
                if (m.getParameterCount() != 0) continue;
                return m.invoke(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Integer safeReadInt(Object obj, String... names) {
        for (String n : names) {
            try {
                // Try method first
                try {
                    Method m = obj.getClass().getMethod(n);
                    if (m.getParameterCount() == 0) {
                        Object v = m.invoke(obj);
                        if (v instanceof Number num) return num.intValue();
                    }
                } catch (Throwable ignored) {}

                // Try field
                try {
                    Field f = obj.getClass().getDeclaredField(n);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof Number num) return num.intValue();
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean containsAny(String s, String[] keys) {
        if (s == null) return false;
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private void dumpInteractionSurface(Object obj) {
        try {
            Class<?> cls = obj.getClass();
            logger.atInfo().log("[RR-TARGET] Surface dump: %s", cls.getName());

            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                logger.atInfo().log("  m: %s -> %s", m.getName(), m.getReturnType().getSimpleName());
            }
            for (Field f : cls.getDeclaredFields()) {
                logger.atInfo().log("  f: %s : %s", f.getName(), f.getType().getSimpleName());
            }
        } catch (Throwable ignored) {}
    }

    private String safeBlockTypeId(BlockType blockType) {
        try {
            Object v = blockType.getClass().getMethod("getId").invoke(blockType);
            return String.valueOf(v);
        } catch (Exception e) {
            return blockType.toString();
        }
    }
}
