
package com.Chris__.Realm_Ruler;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.math.vector.Vector3i;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * Realm Ruler (PlayerInteractLib wired)
 *
 * Goal: detect "press F" interactions on the Flag Stand using PlayerInteractLib,
 * then swap the placed stand block to a colored variant based on what the player is holding
 * (for now), because the stand UI does not emit UseBlockEvent.Pre or ItemContainerChange events
 * in your current server build.
 *
 * IMPORTANT: This file intentionally uses reflection for PlayerInteractLib integration so you
 * don't need it on your compile classpath; it only needs to be present at runtime on the server.
 */
public class Realm_Ruler extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Stand block IDs
    private static final String STAND_EMPTY  = "Flag_Stand";
    private static final String STAND_RED    = "Flag_Stand_Red";
    private static final String STAND_BLUE   = "Flag_Stand_Blue";
    private static final String STAND_WHITE  = "Flag_Stand_White";
    private static final String STAND_YELLOW = "Flag_Stand_Yellow";

    // Flag item IDs (your duplicated swords-as-flags)
    private static final String FLAG_RED    = "Realm_Ruler_Flag_Red";
    private static final String FLAG_BLUE   = "Realm_Ruler_Flag_Blue";
    private static final String FLAG_WHITE  = "Realm_Ruler_Flag_White";
    private static final String FLAG_YELLOW = "Realm_Ruler_Flag_Yellow";

    private static final Message MSG_DEBUG_HIT =
            Message.raw("[RealmRuler] Flag stand interaction detected.");

    // spam limit (Phase 2/3 introspection is chatty; keep bounded)
    private static int PI_DEBUG_LIMIT = 400;
    private static int USEBLOCK_DEBUG_LIMIT = 30;

    // Keep one-time dumps from spamming every interaction
    private final Set<Class<?>> dumpedInteractionClasses = ConcurrentHashMap.newKeySet();

    // We keep this as a fallback if UseBlock ever fires (rare with your "F" flow)
    private volatile BlockLocation pendingStandLocation = null;

    // One-time warnings to avoid log spam
    private final Set<String> warnedMissingStandKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> warnedMissingChunkIndices = ConcurrentHashMap.newKeySet();

    // ----- EyeSpy-style "what block is the player looking at?" tracker -----
    // We raycast every tick and remember each player's current looked-at block, keyed by UUID string.
    // This lets us "join" PlayerInteractLib (uuid + Use) with a reliable target block position.
    private static final double LOOK_RAYCAST_RANGE = 5.0d;
    private static final long LOOK_FRESH_NANOS = 250_000_000L; // 250ms window

    private final Map<String, LookTarget> lookByUuid = new ConcurrentHashMap<>();


    public Realm_Ruler(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from " + this.getName() + " version " + this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up Realm Ruler " + this.getName());

        // Keep your test command for debugging
        this.getCommandRegistry().registerCommand(
                new ExampleCommand(this.getName(), this.getManifest().getVersion().toString())
        );

        // Legacy: may not fire for the stand, but useful signal if it does.
        this.getEventRegistry().register(UseBlockEvent.Pre.class, this::onUseBlock);
        LOGGER.atInfo().log("Registered UseBlockEvent.Pre listener.");

        // EyeSpy-style per-tick raycast tracker (what block each player is looking at)
        this.getEntityStoreRegistry().registerSystem(new LookTargetTrackerSystem());
        LOGGER.atInfo().log("Registered LookTargetTrackerSystem (raycast per tick).");

        // Primary: PlayerInteractLib hookup (reflection so compilation doesn't require its jar)
        tryRegisterPlayerInteractLib();
    }

    private void onUseBlock(UseBlockEvent.Pre event) {
        InteractionType type = event.getInteractionType();

        if (type != InteractionType.Primary && type != InteractionType.Secondary && type != InteractionType.Use) {
            return;
        }

        BlockType blockType = event.getBlockType();
        String clickedId = safeBlockTypeId(blockType);

        InteractionContext ctx = event.getContext();
        ItemStack held = ctx.getHeldItem();
        String heldId = (held == null) ? "<empty>" : safeItemId(held);

        boolean isStand = isStandId(clickedId);

        if (USEBLOCK_DEBUG_LIMIT-- > 0 || isStand) {
            LOGGER.atInfo().log("[RR-USEBLOCK] type=%s clickedId=%s heldId=%s isStand=%s",
                    type, clickedId, heldId, isStand);
        }

        if (isStand) {
            sendPlayerMessage(ctx, MSG_DEBUG_HIT);
            rememberStandLocationFromUseBlock(event, ctx);
        }
    }

    /**
     * PlayerInteractLib integration.
     *
     * We avoid compile-time deps by:
     *  - finding the plugin via PluginManager (reflection)
     *  - pulling its publisher via getPublisher()
     *  - calling subscribe(Consumer<Event>)
     */
    private void tryRegisterPlayerInteractLib() {
        try {
            Object pm = PluginManager.get();

            Object libPlugin = findPluginBestEffort(pm,
                    "Hytale:PlayerInteractLib",
                    "PlayerInteractLib",
                    "hytale:playerinteractlib",
                    "Hytale:playerinteractlib"
            );

            if (libPlugin == null) {
                LOGGER.atWarning().log("[RR-PI] PlayerInteractLib not found via PluginManager. Is it installed and enabled?");
                return;
            }

            Method getPublisher = libPlugin.getClass().getMethod("getPublisher");
            Object publisherObj = getPublisher.invoke(libPlugin);
            if (publisherObj == null) {
                LOGGER.atWarning().log("[RR-PI] PlayerInteractLib returned a null publisher.");
                return;
            }

            if (!(publisherObj instanceof java.util.concurrent.SubmissionPublisher<?>)) {
                LOGGER.atWarning().log("[RR-PI] Unexpected publisher type: %s", publisherObj.getClass().getName());
                return;
            }

            @SuppressWarnings("unchecked")
            java.util.concurrent.SubmissionPublisher<PlayerInteractionEvent> publisher =
                    (java.util.concurrent.SubmissionPublisher<PlayerInteractionEvent>) publisherObj;

            Flow.Subscriber<PlayerInteractionEvent> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                    LOGGER.atInfo().log("[RR-PI] Subscribed OK. publisher=%s", publisherObj.getClass().getName());
                }

                @Override
                public void onNext(PlayerInteractionEvent event) {
                    onPlayerInteraction(event);
                }

                @Override
                public void onError(Throwable throwable) {
                    LOGGER.atWarning().withCause(throwable).log("[RR-PI] publisher error");
                }

                @Override
                public void onComplete() {
                    LOGGER.atInfo().log("[RR-PI] publisher completed");
                }
            };

            publisher.subscribe(subscriber);

        } catch (Throwable t) {
            LOGGER.atSevere().withCause(t).log("[RR-PI] Failed to register PlayerInteractLib event subscription.");
        }
    }

    /**
     * Try a few PluginManager APIs without hardcoding PluginIdentifier types.
     */
    private Object findPluginBestEffort(Object pluginManager, String... names) {
        // Try common method shapes:
        // - getPlugin(String)
        // - getPluginByName(String)
        // - getPlugin(Object) with a string arg
        String[] methodNames = new String[] { "getPlugin", "getPluginByName", "getPluginOrNull" };

        for (String name : names) {
            for (String mName : methodNames) {
                try {
                    Method m = pluginManager.getClass().getMethod(mName, String.class);
                    Object p = m.invoke(pluginManager, name);
                    if (p != null) return p;
                } catch (Throwable ignored) {}
            }
        }

        // As a last resort: scan plugins list if a getter exists
        try {
            Method m = pluginManager.getClass().getMethod("getPlugins");
            Object plugins = m.invoke(pluginManager);
            if (plugins instanceof Iterable<?> it) {
                for (Object p : it) {
                    if (p == null) continue;
                    String s = p.toString().toLowerCase(Locale.ROOT);
                    for (String wanted : names) {
                        if (s.contains(wanted.toLowerCase(Locale.ROOT))) return p;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    // Toggle stand swapping once we have reliable (world,x,y,z) extraction
    private static final boolean ENABLE_STAND_SWAP = true;

    // Phase 1: ignore item-in-hand and simply toggle Empty <-> Blue on any stand interaction
    private static final boolean PHASE1_TOGGLE_BLUE_ONLY = true;



    /**
     * PlayerInteractLib event handler (Phase 3):
     * - log the event
     * - extract the block position from the SyncInteractionChain (nested hit/target)
     * - (optionally) swap the stand variant based on the flag in hand
     */
    private void onPlayerInteraction(PlayerInteractionEvent event) {
        boolean shouldLog = (PI_DEBUG_LIMIT-- > 0);
        InteractionType type = safeInteractionType(event);
        String uuid = safeUuid(event);
        String itemInHand = safeItemInHandId(event);
        Object chain = safeInteractionChain(event);

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
            LOGGER.atWarning().withCause(t).log("[RR-PI] Failed while extracting block location");
        }

        // If we used the look-tracker fallback, log it (once per interaction, not per tick).
        if (look != null && look.basePos != null) {
            long ageMs = (System.nanoTime() - look.nanoTime) / 1_000_000L;
            if (shouldLog) LOGGER.atInfo().log("[RR-LOOK] uuid=%s lookBlock=%s pos=%d,%d,%d ageMs=%d",
                    uuid,
                    String.valueOf(look.blockId),
                    look.basePos.x, look.basePos.y, look.basePos.z,
                    ageMs);
        }

        if (shouldLog) LOGGER.atInfo().log("[RR-PI] uuid=%s type=%s item=%s chain=%s pos=%s",
                uuid,
                String.valueOf(type),
                itemInHand,
                (chain == null ? "<null>" : chain.getClass().getName()),
                (loc == null ? "<none>" : (loc.x + "," + loc.y + "," + loc.z)));

        if (loc == null || loc.world == null) return;

        // Determine clicked block type at that position
        String clickedId = tryGetBlockIdAt(loc.world, loc.x, loc.y, loc.z);
        if (clickedId == null) return;

        if (!isStandId(clickedId)) return;

        String desiredStand;
        if (PHASE1_TOGGLE_BLUE_ONLY) {
            // Phase 1: any interaction with any stand toggles Empty <-> Blue.
            // Later we'll swap to "based on item in hand" once the signal is reliable.
            desiredStand = STAND_BLUE.equals(clickedId) ? STAND_EMPTY : STAND_BLUE;
        } else {
            // Future: choose variant based on the flag item in-hand.
            desiredStand = STAND_EMPTY;
            if (FLAG_RED.equals(itemInHand)) desiredStand = STAND_RED;
            else if (FLAG_BLUE.equals(itemInHand)) desiredStand = STAND_BLUE;
            else if (FLAG_WHITE.equals(itemInHand)) desiredStand = STAND_WHITE;
            else if (FLAG_YELLOW.equals(itemInHand)) desiredStand = STAND_YELLOW;
        }



        if (!ENABLE_STAND_SWAP) {
            LOGGER.atInfo().log("[RR] (dry-run) would swap stand @ %d,%d,%d from %s -> %s",
                    loc.x, loc.y, loc.z, clickedId, desiredStand);
            return;
        }

        setStandBlock(loc, desiredStand);
    }

    private InteractionType safeInteractionType(PlayerInteractionEvent e) {
        try { return e.interactionType(); } catch (Throwable ignored) {}
        try {
            Object v = safeCall(e, "getInteractionType", "interactionType");
            if (v instanceof InteractionType it) return it;
            if (v != null) return InteractionType.valueOf(String.valueOf(v));
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeUuid(PlayerInteractionEvent e) {
        try { return e.uuid(); } catch (Throwable ignored) {}
        try {
            Object v = safeCall(e, "getUuid", "uuid");
            return v == null ? "<null>" : String.valueOf(v);
        } catch (Throwable ignored) {}
        return "<null>";
    }

    private String safeItemInHandId(PlayerInteractionEvent e) {
        try { return e.itemInHandId(); } catch (Throwable ignored) {}
        try {
            Object v = safeCall(e, "getItemInHandId", "itemInHandId");
            return v == null ? "<empty>" : String.valueOf(v);
        } catch (Throwable ignored) {}
        return "<empty>";
    }

    private Object safeInteractionChain(PlayerInteractionEvent e) {
        try { return e.interaction(); } catch (Throwable ignored) {}
        try { return safeCall(e, "getInteraction", "interaction"); } catch (Throwable ignored) {}
        return null;
    }



    private LookTarget getFreshLookTarget(String uuid) {
        if (uuid == null || uuid.isEmpty() || "<null>".equals(uuid)) return null;
        LookTarget t = lookByUuid.get(uuid);
        if (t == null) return null;
        long age = System.nanoTime() - t.nanoTime;
        if (age > LOOK_FRESH_NANOS) return null;
        return t;
    }

    private BlockLocation tryLocationFromLook(String uuid) {
        if (uuid == null || uuid.isEmpty() || "<null>".equals(uuid)) return null;
        LookTarget t = getFreshLookTarget(uuid);
        if (t == null) return null;

        if (t.world == null || t.basePos == null) return null;
        return new BlockLocation(t.world, t.basePos.x, t.basePos.y, t.basePos.z);
    }

    private BlockLocation tryExtractBlockLocation(String uuid, PlayerInteractionEvent event, Object chain) {
        World world = null;

        // Some builds may expose the World directly on the event
        try {
            Object w = safeCall(event, "getWorld", "world");
            if (w instanceof World ww) world = ww;
        } catch (Throwable ignored) {}

        // Drill into the SyncInteractionChain / hit result to find x,y,z
        BlockLocation found = extractPosRecursive(chain, world, 0, "chain");
        if (found != null) return found;

        // Fallback: look-tracker (EyeSpy raycast) keyed by UUID.
        BlockLocation lookLoc = tryLocationFromLook(uuid);
        if (lookLoc != null) return lookLoc;

        // Fallback: if the legacy UseBlock hook ever remembered a location, use it.
        if (pendingStandLocation != null) {
            if (pendingStandLocation.world != null) return pendingStandLocation;
            if (world != null) return new BlockLocation(world, pendingStandLocation.x, pendingStandLocation.y, pendingStandLocation.z);
        }
        return null;
    }

    private BlockLocation extractPosRecursive(Object obj, World world, int depth, String path) {
        if (obj == null) return null;
        if (depth > 4) return null;

        // Direct "vector" shape?
        BlockLocation direct = tryReadXYZ(obj, world);
        if (direct != null) {
            LOGGER.atInfo().log("[RR-PI] FOUND pos via %s (%s)", path, obj.getClass().getName());
            return direct;
        }

        // One-time hint dump for this class so we can see what to target next
        Class<?> cls = obj.getClass();
        if (dumpedInteractionClasses.add(cls)) {
            dumpInteractionSurface(obj);
        }

        // Prefer methods/fields that smell like hit/target/pos
        String[] keys = new String[] { "hit", "target", "block", "pos", "position", "location", "coord", "world", "chunk" };

        // Methods first
        for (Method m : cls.getMethods()) {
            try {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == Void.TYPE) continue;
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(name, keys)) continue;

                Object child = m.invoke(obj);
                if (child == null) continue;
                if (child instanceof String) continue;
                if (child.getClass().isPrimitive()) continue;
                if (child instanceof Number) continue;

                // If this child is the world, capture it
                if (world == null && child instanceof World ww) world = ww;

                BlockLocation loc = extractPosRecursive(child, world, depth + 1, path + "." + m.getName() + "()" );
                if (loc != null) return loc;
            } catch (Throwable ignored) {}
        }

        // Then fields
        for (var f : cls.getDeclaredFields()) {
            try {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(name, keys)) continue;
                f.setAccessible(true);
                Object child = f.get(obj);
                if (child == null) continue;

                if (world == null && child instanceof World ww) world = ww;

                BlockLocation loc = extractPosRecursive(child, world, depth + 1, path + "." + f.getName());
                if (loc != null) return loc;
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private BlockLocation tryReadXYZ(Object obj, World world) {
        try {
            // Common getter shapes: getX()/getY()/getZ() or x()/y()/z()
            Integer x = tryGetInt(obj, "getX", "x");
            Integer y = tryGetInt(obj, "getY", "y");
            Integer z = tryGetInt(obj, "getZ", "z");
            if (x != null && y != null && z != null) return new BlockLocation(world, x, y, z);

            // Alternative shapes: blockX/blockY/blockZ
            x = tryGetInt(obj, "getBlockX", "blockX");
            y = tryGetInt(obj, "getBlockY", "blockY");
            z = tryGetInt(obj, "getBlockZ", "blockZ");
            if (x != null && y != null && z != null) return new BlockLocation(world, x, y, z);
        } catch (Throwable ignored) {}
        return null;
    }

    private Integer tryGetInt(Object obj, String getterName, String fieldName) {
        try {
            Method m = obj.getClass().getMethod(getterName);
            if (m.getParameterCount() == 0) {
                Object v = m.invoke(obj);
                if (v instanceof Number n) return n.intValue();
            }
        } catch (Throwable ignored) {}
        try {
            Method m = obj.getClass().getMethod(fieldName);
            if (m.getParameterCount() == 0) {
                Object v = m.invoke(obj);
                if (v instanceof Number n) return n.intValue();
            }
        } catch (Throwable ignored) {}
        try {
            var f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}
        try {
            var f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean containsAny(String haystackLower, String[] needlesLower) {
        for (String n : needlesLower) {
            if (haystackLower.contains(n)) return true;
        }
        return false;
    }

    private void dumpInteractionSurface(Object obj) {
        try {
            Class<?> cls = obj.getClass();
            LOGGER.atInfo().log("[RR-PI] ---- Phase3 surface dump (%s) ----", cls.getName());

            int shown = 0;
            for (Method m : cls.getMethods()) {
                if (shown >= 40) break;
                if (m.getParameterCount() != 0) continue;
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(n, new String[]{"hit","target","block","pos","position","world","coord","location"})) continue;
                LOGGER.atInfo().log("[RR-PI]   m: %s -> %s", m.getName(), m.getReturnType().getTypeName());
                shown++;
            }

            shown = 0;
            for (var f : cls.getDeclaredFields()) {
                if (shown >= 40) break;
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(n, new String[]{"hit","target","block","pos","position","world","coord","location"})) continue;
                LOGGER.atInfo().log("[RR-PI]   f: %s : %s", f.getName(), f.getType().getTypeName());
                shown++;
            }
        } catch (Throwable ignored) {}
    }

    private boolean isStandId(String id) {
        return STAND_EMPTY.equals(id) ||
                STAND_RED.equals(id) ||
                STAND_BLUE.equals(id) ||
                STAND_WHITE.equals(id) ||
                STAND_YELLOW.equals(id);
    }

    private String tryGetBlockIdAt(World world, int x, int y, int z) {
        try {
            // Possible method shapes:
            // - getBlockType(int,int,int) -> BlockType
            // - getBlock(int,int,int) -> BlockType
            // - getBlockType(Vector3i) ...
            Method m = null;
            try { m = world.getClass().getMethod("getBlockType", int.class, int.class, int.class); } catch (Throwable ignored) {}
            if (m == null) {
                try { m = world.getClass().getMethod("getBlock", int.class, int.class, int.class); } catch (Throwable ignored) {}
            }
            if (m == null) return null;

            Object bt = m.invoke(world, x, y, z);
            if (bt instanceof BlockType blockType) {
                return safeBlockTypeId(blockType);
            }
            return String.valueOf(bt);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- Block placement ----------

    private void setStandBlock(BlockLocation loc, String standKey) {
        if (loc == null || loc.world == null) return;

        int newBlockId = BlockType.getAssetMap().getIndex(standKey);
        if (newBlockId == Integer.MIN_VALUE) {
            if (warnedMissingStandKeys.add(standKey)) {
                LOGGER.atWarning().log("[RR] stand asset id not found for %s", standKey);
            }
            return;
        }

        long chunkIndex = ChunkUtil.indexChunkFromBlock(loc.x, loc.z);
        WorldChunk chunk = loc.world.getChunk(chunkIndex);
        if (chunk == null) {
            if (warnedMissingChunkIndices.add(chunkIndex)) {
                LOGGER.atWarning().log("[RR] chunk not loaded for swap at %d,%d,%d (chunkIndex=%d)", loc.x, loc.y, loc.z, chunkIndex);
            }
            return;
        }

        chunk.setBlock(loc.x, loc.y, loc.z, newBlockId, 0);
        LOGGER.atInfo().log("[RR] Swapped stand @ %d,%d,%d -> %s", loc.x, loc.y, loc.z, standKey);
    }

    // ---------- Helpers ----------

    private void rememberStandLocationFromUseBlock(UseBlockEvent.Pre event, InteractionContext ctx) {
        try {
            World world = null;

            try {
                Method m = event.getClass().getMethod("getWorld");
                Object w = m.invoke(event);
                if (w instanceof World ww) world = ww;
            } catch (Throwable ignored) {}

            if (world == null) {
                try {
                    Method m = ctx.getClass().getMethod("getWorld");
                    Object w = m.invoke(ctx);
                    if (w instanceof World ww) world = ww;
                } catch (Throwable ignored) {}
            }

            if (world == null) return;

            Object pos = safeCall(event, "getTargetBlock", "targetBlock");
            if (pos == null) return;

            int x = readVecInt(pos, "getX", "x");
            int y = readVecInt(pos, "getY", "y");
            int z = readVecInt(pos, "getZ", "z");

            pendingStandLocation = new BlockLocation(world, x, y, z);
        } catch (Throwable ignored) {}
    }

    private Object safeCall(Object obj, String... methodNames) {
        for (String n : methodNames) {
            try {
                Method m = obj.getClass().getMethod(n);
                if (m.getParameterCount() != 0) continue;
                return m.invoke(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private InteractionType safeEnum(Object obj, String... methodNames) {
        Object v = safeCall(obj, methodNames);
        if (v instanceof InteractionType it) return it;
        try {
            // Some implementations return an enum with same name; try to map
            if (v != null) {
                String s = String.valueOf(v);
                return InteractionType.valueOf(s);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int readVecInt(Object vec, String getterName, String fieldName) {
        try {
            Method m = vec.getClass().getMethod(getterName);
            Object v = m.invoke(vec);
            if (v instanceof Integer i) return i;
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        try {
            var f = vec.getClass().getField(fieldName);
            Object v = f.get(vec);
            if (v instanceof Integer i) return i;
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        return 0;
    }

    private String safeItemIdFromUnknown(Object maybeItemStack) {
        if (maybeItemStack == null) return "<empty>";
        try {
            if (maybeItemStack instanceof ItemStack is) return safeItemId(is);
        } catch (Throwable ignored) {}

        try {
            Method m = maybeItemStack.getClass().getMethod("getItemId");
            Object v = m.invoke(maybeItemStack);
            return String.valueOf(v);
        } catch (Throwable ignored) {}

        try {
            Method m = maybeItemStack.getClass().getMethod("itemId");
            Object v = m.invoke(maybeItemStack);
            return String.valueOf(v);
        } catch (Throwable ignored) {}

        return String.valueOf(maybeItemStack);
    }

    private String safeBlockTypeId(BlockType blockType) {
        try {
            Object v = blockType.getClass().getMethod("getId").invoke(blockType);
            return String.valueOf(v);
        } catch (Exception e) {
            return blockType.toString();
        }
    }

    private String safeItemId(ItemStack stack) {
        try {
            return String.valueOf(stack.getItemId());
        } catch (Throwable t) {
            return stack.toString();
        }
    }

    private void sendPlayerMessage(InteractionContext ctx, Message msg) {
        try {
            if (ctx.getCommandBuffer() == null) return;
            if (ctx.getEntity() == null || !ctx.getEntity().isValid()) return;

            Player player = (Player) ctx.getCommandBuffer().getComponent(ctx.getEntity(), Player.getComponentType());
            if (player != null) player.sendMessage(msg);
        } catch (Exception ignored) {}
    }


    // ---------- Look target tracking (EyeSpy technique) ----------

    private static final class LookTarget {
        final World world;
        final Vector3i targetPos; // raw hit
        final Vector3i basePos;   // resolved (handles filler blocks)
        final String blockId;     // best-effort string ID of block type
        final long nanoTime;

        LookTarget(World world, Vector3i targetPos, Vector3i basePos, String blockId, long nanoTime) {
            this.world = world;
            this.targetPos = targetPos;
            this.basePos = basePos;
            this.blockId = blockId;
            this.nanoTime = nanoTime;
        }
    }

    private final class LookTargetTrackerSystem extends EntityTickingSystem<EntityStore> {

        private final Query<EntityStore> query;

        LookTargetTrackerSystem() {
            this.query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());
        }

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

                // Raycast for target block (same call EyeSpy uses)
                Vector3i hit = TargetUtil.getTargetBlock(chunk.getReferenceTo(entityId), LOOK_RAYCAST_RANGE, store);
                if (hit == null) return;

                EntityStore es = (EntityStore) store.getExternalData();
                if (es == null) return;

                World world = es.getWorld();
                if (world == null) return;

                // Resolve correct chunk + filler blocks (same logic as EyeSpy BlockContext.create)
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

            } catch (Throwable ignored) {
                // Keep silent; this ticks a lot.
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

    private String safeUuidFromPlayer(PlayerRef ref, Player player) {
        // Prefer PlayerRef methods (most stable), then Player methods, then toString() fallback.
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


    // ---------- Option A: BlockLocation with coords-only constructor ----------

    private static final class BlockLocation {
        final World world; // can be null if we only have coords
        final int x, y, z;

        BlockLocation(World world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        BlockLocation(int x, int y, int z) {
            this.world = null;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
