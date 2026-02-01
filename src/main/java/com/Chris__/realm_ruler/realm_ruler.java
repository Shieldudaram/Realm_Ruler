
package com.Chris__.Realm_Ruler;

import com.Chris__.Realm_Ruler.core.ModeManager;
import com.Chris__.Realm_Ruler.modes.CtfMode;


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
 * Realm Ruler (Capture-the-Flag building block)
 *
 * What this plugin currently does (Phase 1):
 * - Detects when a player interacts with a placed Flag Stand block.
 * - On interaction, swaps the *placed block* between stand variants (e.g., empty ↔ blue).
 *
 * Why we use PlayerInteractLib instead of vanilla UseBlockEvent:
 * - In your current Hytale server build, opening/closing the stand UI via "F" does NOT reliably
 *   fire UseBlockEvent.Pre, and we don't have a dependable ItemContainerChange-style event either.
 * - PlayerInteractLib provides a higher-level, plugin-friendly "PlayerInteractionEvent" stream that
 *   DOES fire for your "F" interactions, including the player UUID and InteractionType.
 *
 * Key challenge this file solves:
 * - PlayerInteractLib tells us WHO interacted and WHAT kind of interaction occurred,
 *   but it does NOT always tell us WHERE (the target block position).
 * - To bridge that gap, we run a lightweight raycast each tick ("EyeSpy" approach) and remember
 *   what block each player is currently looking at. We then "join":
 *
 *     (uuid + interaction event)  +  (uuid -> looked-at block position)  =>  (world + x,y,z)
 *
 * Threading note (important for future edits):
 * - PlayerInteractLib events may arrive off the main tick thread (asynchronous).
 * - World/block writes should happen on the server tick thread to avoid race conditions.
 *   (Right now our swap path is written to be safe / defensively coded; if you later see
 *   weirdness, we can move all writes into a tick-queued task system.)
 *
 * Design goals for readability:
 * - Keep the "event detection" logic separate from the "world edit" logic.
 * - Keep debug/introspection bounded so logs don't become unusable.
 *
 * Reflection note (why it exists here):
 * - We intentionally use reflection for PlayerInteractLib integration so this plugin can compile
 *   even if PlayerInteractLib is not on the build classpath.
 * - At runtime, if PlayerInteractLib is present, we hook into it; if not, the plugin still loads,
 *   just with reduced functionality (fallback events only).
 *
 * // Comment tags legend:(right now not all things use these but they should be added over time when possible)
 * // COMPAT  = compatibility shim for different server/plugin API shapes
 * // DEV     = debug/introspection helper (useful while reverse engineering)
 * // OPTIONAL= fallback path; may not run in current build but kept for resilience
 * // ROADMAP = planned future work (Phase 2+)
 */
public class Realm_Ruler extends JavaPlugin {

    private ModeManager modeManager;

    private void setupModes() {
        modeManager = new ModeManager();
        modeManager.register(new CtfMode());
        modeManager.setActive("ctf");
    }




    /** Logger provided by the Hytale server runtime. */
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // These toggles exist to help isolate bugs and reduce noise while testing.
    // They should NOT change core gameplay logic, only enable/disable optional telemetry/fallbacks.
    private static final boolean ENABLE_USEBLOCK_FALLBACK = true;
    private static final boolean ENABLE_LOOK_TRACKER = true;



    // -------------------------------------------------------------------------
    // Asset IDs (strings must match your JSON block/item IDs exactly)
    // -------------------------------------------------------------------------

    /** Stand block IDs (placed in the world). */
    private static final String STAND_EMPTY  = "Flag_Stand";
    private static final String STAND_RED    = "Flag_Stand_Red";
    private static final String STAND_BLUE   = "Flag_Stand_Blue";
    private static final String STAND_WHITE  = "Flag_Stand_White";
    private static final String STAND_YELLOW = "Flag_Stand_Yellow";

    /** Flag item IDs (items in player inventory / hand). */
    private static final String FLAG_RED    = "Realm_Ruler_Flag_Red";
    private static final String FLAG_BLUE   = "Realm_Ruler_Flag_Blue";
    private static final String FLAG_WHITE  = "Realm_Ruler_Flag_White";
    private static final String FLAG_YELLOW = "Realm_Ruler_Flag_Yellow";

    /** Small in-game message used for quick sanity testing. */
    private static final Message MSG_DEBUG_HIT =
            Message.raw("[RealmRuler] Flag stand interaction detected.");

    // -------------------------------------------------------------------------
    // Debug / log safety rails
    // -------------------------------------------------------------------------

    /**
     * Limits how many detailed PlayerInteractLib debug logs we print on startup/testing.
     * IMPORTANT: This should NEVER disable core logic; it should only reduce log spam.
     */
    private static int PI_DEBUG_LIMIT = 400;

    /**
     * Limits how many UseBlock fallback logs we print (UseBlockEvent can be noisy when enabled).
     */
    private static int USEBLOCK_DEBUG_LIMIT = 30;

    /**
     * We do reflection-based "surface dumps" to discover what data exists inside interaction chains.
     * This set prevents dumping the same class repeatedly.
     */
    private final Set<Class<?>> dumpedInteractionClasses = ConcurrentHashMap.newKeySet();

    /**
     * Fallback: sometimes UseBlockEvent provides a target position when PlayerInteractLib doesn't.
     * We store the last known stand target as a "best effort" fallback.
     *
     * volatile because it may be written and read from different callbacks (thread safety).
     */
    private volatile BlockLocation pendingStandLocation = null;

    /**
     * These sets are just "warn-once" guards so the log doesn't become a firehose.
     * - warnedMissingStandKeys: we attempted to swap to a stand ID that wasn't found in AssetMap.
     * - warnedMissingChunkIndices: chunk wasn't loaded when we tried to read/write at that position.
     */
    private final Set<String> warnedMissingStandKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> warnedMissingChunkIndices = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------
    // EyeSpy-style looked-at block tracker (UUID -> what block they are aiming at)
    // -------------------------------------------------------------------------

    /** How far the raycast should check for a targeted block. */
    private static final double LOOK_RAYCAST_RANGE = 5.0d;

    /**
     * Freshness window: we only trust looked-at results captured very recently.
     * This prevents "stale aim" where a player looked somewhere else 2 seconds ago.
     */
    private static final long LOOK_FRESH_NANOS = 250_000_000L; // 250ms window

    /**
     * Latest looked-at block info per player UUID (string form).
     * Used to "join" PlayerInteractLib events to a concrete (world + x,y,z).
     */
    private final Map<String, LookTarget> lookByUuid = new ConcurrentHashMap<>();

    public Realm_Ruler(@Nonnull JavaPluginInit init) {
        super(init);
        setupModes();
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }





    @Override
    protected void setup() {
        // ---------------------------------------------------------------------
        // setup() is the plugin "boot sequence".
        //
        // Think of it as: "wire up inputs, then wire up background sensors, then
        // enable the main interaction stream".
        //
        // The order here matters conceptually:
        //  1) Register commands (manual testing hooks)
        //  2) Register fallback events (if they fire, they can help us debug)
        //  3) Register tick systems (our passive 'sensors' that run constantly)
        //  4) Register external libraries (PlayerInteractLib stream)
        //
        // If something breaks, we can comment out sections in this method to
        // isolate the failure quickly.
        // ---------------------------------------------------------------------

        LOGGER.atInfo().log("Setting up Realm Ruler %s", this.getName());

        // ---------------------------------------------------------------------
        // 1) Command registration (debug/testing convenience)
        //
        // This registers your /test command (ExampleCommand).
        // Keeping a tiny command in a mod is useful because it gives you a
        // reliable, manual way to confirm:
        //  - the plugin loaded
        //  - command registry works
        //  - permissions / chat output behave as expected
        // ---------------------------------------------------------------------
        this.getCommandRegistry().registerCommand(
                new ExampleCommand(this.getName(), this.getManifest().getVersion().toString())
        );

        // ---------------------------------------------------------------------
        // 2) Fallback event registration (UseBlockEvent.Pre)
        //
        // In your current server build, pressing "F" to open/close stand UI does
        // NOT reliably trigger UseBlockEvent.Pre. Still, we keep this listener
        // because:
        //  - it sometimes fires for other block interactions (useful signal)
        //  - it gives us a second path to learn "what block was targeted"
        //  - if future server builds change behavior, we automatically benefit
        //
        // The onUseBlock() handler is treated as "bonus telemetry", not required
        // for the main mechanic to work.
        // ---------------------------------------------------------------------
        if (ENABLE_USEBLOCK_FALLBACK) {
            this.getEventRegistry().register(UseBlockEvent.Pre.class, this::onUseBlock);
            LOGGER.atInfo().log("Registered UseBlockEvent.Pre listener (fallback telemetry).");
        } else {
            LOGGER.atInfo().log("UseBlockEvent.Pre fallback is disabled (ENABLE_USEBLOCK_FALLBACK=false).");
        }


        // ---------------------------------------------------------------------
        // 3) Tick system registration: LookTargetTrackerSystem (EyeSpy approach)
        //
        // PlayerInteractLib reliably tells us "player UUID + interaction type",
        // but it does not always give us an exact block position.
        //
        // This system runs every tick and raycasts from each player to find the
        // block they are currently looking at. We store:
        //   uuid -> (world + x,y,z + blockId + timestamp)
        //
        // Later, when PlayerInteractLib says "UUID interacted", we can look up
        // the most recent raycast for that UUID and infer the target block.
        //
        // This is the core "bridge" that turns:
        //   WHO interacted  ->  WHERE they interacted (most likely).
        // ---------------------------------------------------------------------
        if (ENABLE_LOOK_TRACKER) {
            this.getEntityStoreRegistry().registerSystem(new LookTargetTrackerSystem());
            LOGGER.atInfo().log("Registered LookTargetTrackerSystem (raycast per tick).");
        } else {
            LOGGER.atInfo().log("LookTargetTrackerSystem is disabled (ENABLE_LOOK_TRACKER=false).");
        }


        // ---------------------------------------------------------------------
        // 4) Primary interaction stream: PlayerInteractLib subscription
        //
        // This is our main driver for the stand toggle mechanic.
        //
        // We use reflection in tryRegisterPlayerInteractLib() so this plugin can
        // still compile (and even load) without the PlayerInteractLib jar on the
        // build classpath. At runtime, if PlayerInteractLib is installed, we
        // subscribe and start receiving PlayerInteractionEvent callbacks.
        //
        // If PlayerInteractLib is missing:
        //  - tryRegisterPlayerInteractLib() should log a warning
        //  - the plugin still loads, but stand detection will be less reliable
        // ---------------------------------------------------------------------
        tryRegisterPlayerInteractLib();
    }

    /**
     * Fallback block-interaction listener.
     *
     * Why this exists:
     * - In your current build, the Flag Stand's "press F" UI flow does NOT reliably trigger this event.
     * - However, UseBlockEvent.Pre *does* fire for many other interactions, and sometimes for stands,
     *   depending on the block and interaction path.
     *
     * How we use it:
     * - Primarily as "telemetry": it can tell us what the engine thinks the player clicked and what
     *   they were holding.
     * - Secondarily as a "position hint": if it fires on the stand, we record the stand's location as
     *   a fallback, since PlayerInteractLib events may not include the target position.
     *
     * Important: This is not the primary interaction pipeline. PlayerInteractLib is.
     */
    private void onUseBlock(UseBlockEvent.Pre event) {
        if (modeManager != null) {
            modeManager.dispatchPlayerAction(event);
        }

        // The server describes interaction as Primary/Secondary/Use/etc.
        // We ignore types outside these common "block use" buckets to reduce noise.
        InteractionType type = event.getInteractionType();

        if (type != InteractionType.Primary && type != InteractionType.Secondary && type != InteractionType.Use) {
            return;
        }

        // What block type was clicked?
        BlockType blockType = event.getBlockType();
        String clickedId = safeBlockTypeId(blockType);

        // InteractionContext contains the player and the held item.
        InteractionContext ctx = event.getContext();
        ItemStack held = ctx.getHeldItem();

        // We normalize the held item into a string ID (or "<empty>" if none).
        // NOTE: safeItemId is defensive to avoid NPEs when held item metadata is missing.
        String heldId = (held == null) ? "<empty>" : safeItemId(held);

        // Stand detection is purely string-match against known stand IDs.
        boolean isStand = isStandId(clickedId);

        // Log a limited number of events to prevent log spam.
        // If it *is* a stand, we always log it even if we exceeded the limit.
        if (USEBLOCK_DEBUG_LIMIT-- > 0 || isStand) {
            LOGGER.atInfo().log("[RR-USEBLOCK] type=%s clickedId=%s heldId=%s isStand=%s",
                    type, clickedId, heldId, isStand);
        }

        if (isStand) {
            // Optional: in-game confirmation (lets you test without staring at server logs).
            sendPlayerMessage(ctx, MSG_DEBUG_HIT);

            // IMPORTANT:
            // We record the stand location from this event as a fallback.
            // Later, if PlayerInteractLib can't give us a position, we may use this remembered location.
            rememberStandLocationFromUseBlock(event, ctx);
        }
    }

    /**
     * PlayerInteractLib integration.
     *
     * This is the primary interaction stream for your "press F" stand interactions.
     *
     * Why reflection:
     * - We do not want PlayerInteractLib as a compile-time dependency.
     * - Reflection lets this plugin compile without the library JAR present.
     * - If PlayerInteractLib is installed at runtime, we subscribe to it.
     *
     * High-level steps:
     *  1) Ask PluginManager for the PlayerInteractLib plugin instance
     *  2) Call getPublisher() on that plugin
     *  3) Subscribe a Flow.Subscriber so we receive PlayerInteractionEvent callbacks
     */
    private void tryRegisterPlayerInteractLib() {
        try {
            // PluginManager is the global registry for plugins at runtime.
            Object pm = PluginManager.get();

            // PlayerInteractLib might be registered under slightly different name strings depending on build,
            // so we try several candidates.
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

            // PlayerInteractLib is expected to expose: getPublisher()
            Method getPublisher = libPlugin.getClass().getMethod("getPublisher");
            Object publisherObj = getPublisher.invoke(libPlugin);

            if (publisherObj == null) {
                LOGGER.atWarning().log("[RR-PI] PlayerInteractLib returned a null publisher.");
                return;
            }

            // We expect a SubmissionPublisher<PlayerInteractionEvent>.
            // This check prevents ClassCastException with a clearer log message.
            if (!(publisherObj instanceof java.util.concurrent.SubmissionPublisher<?>)) {
                LOGGER.atWarning().log("[RR-PI] Unexpected publisher type: %s", publisherObj.getClass().getName());
                return;
            }

            @SuppressWarnings("unchecked")
            java.util.concurrent.SubmissionPublisher<PlayerInteractionEvent> publisher =
                    (java.util.concurrent.SubmissionPublisher<PlayerInteractionEvent>) publisherObj;

            // Flow.Subscriber is the Java reactive-streams style subscription interface.
            // Once subscribed, PlayerInteractLib will call onNext(...) for each interaction event.
            Flow.Subscriber<PlayerInteractionEvent> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    // Request all events. This is the normal pattern for "we want the full stream".
                    subscription.request(Long.MAX_VALUE);
                    LOGGER.atInfo().log("[RR-PI] Subscribed OK. publisher=%s", publisherObj.getClass().getName());
                }

                @Override
                public void onNext(PlayerInteractionEvent event) {
                    // Main per-event handler (our logic lives there).
                    onPlayerInteraction(event);
                }

                @Override
                public void onError(Throwable throwable) {
                    // If the publisher dies or the stream errors, we log it.
                    LOGGER.atWarning().withCause(throwable).log("[RR-PI] publisher error");
                }

                @Override
                public void onComplete() {
                    // Generally only happens if the plugin shuts down.
                    LOGGER.atInfo().log("[RR-PI] publisher completed");
                }
            };

            publisher.subscribe(subscriber);

        } catch (Throwable t) {
            // Any reflection issues or unexpected runtime mismatch lands here.
            LOGGER.atSevere().withCause(t).log("[RR-PI] Failed to register PlayerInteractLib event subscription.");
        }
    }

    /**
     * Best-effort plugin lookup without compile-time PluginIdentifier dependencies.
     *
     * We try multiple possible PluginManager APIs because server/plugin builds can differ:
     * - getPlugin(String)
     * - getPluginByName(String)
     * - getPluginOrNull(String)
     * If those fail, we attempt getPlugins() and scan for something that "looks like" the plugin.
     *
     * This keeps the plugin resilient to minor API naming changes.
     */
    private Object findPluginBestEffort(Object pluginManager, String... names) {
        // Try common method shapes:
        // - getPlugin(String)
        // - getPluginByName(String)
        // - getPluginOrNull(String)
        String[] methodNames = new String[] { "getPlugin", "getPluginByName", "getPluginOrNull" };

        for (String name : names) {
            for (String mName : methodNames) {
                try {
                    Method m = pluginManager.getClass().getMethod(mName, String.class);
                    Object p = m.invoke(pluginManager, name);
                    if (p != null) return p;
                } catch (Throwable ignored) {
                    // Ignored on purpose: we are probing multiple possible method names.
                }
            }
        }

        // Last resort: scan plugins list if a getter exists
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
        } catch (Throwable ignored) {
            // Ignored on purpose: getPlugins() might not exist in some builds.
        }

        return null;
    }

// -----------------------------------------------------------------------------
// Feature switches
// -----------------------------------------------------------------------------

    /**
     * When true, we actually modify the world (swap blocks). When false, we only log what we would do.
     * This is useful when you're still verifying that position extraction is correct.
     */
    private static final boolean ENABLE_STAND_SWAP = true;

    /**
     * Phase 1: ignore item-in-hand and simply toggle Empty <-> Blue on any stand interaction.
     * Phase 2+: set this false and use itemInHand to choose stand color based on flags.
     */
    private static final boolean PHASE1_TOGGLE_BLUE_ONLY = true;

    /**
     * PlayerInteractLib event handler.
     *
     * Responsibilities:
     *  1) Extract stable identifiers from the event (uuid, interaction type, item-in-hand)
     *  2) Resolve a target block position (prefer chain extraction; fallback to look tracker)
     *  3) Verify the clicked block is one of our stand IDs
     *  4) Decide which stand variant we want
     *  5) Perform the swap (or dry-run if disabled)
     *
     * NOTE:
     * - "press F" tends to show up as InteractionType.Use in your logs.
     * - PlayerInteractLib does not always provide a direct block position, which is why we use the look tracker.
     */
    private void onPlayerInteraction(PlayerInteractionEvent event) {
        if (modeManager != null) {
            modeManager.dispatchPlayerAction(event);
        }

        // Log-limiter for "chatty" per-event debug logging.
        // IMPORTANT: This should NEVER stop functionality, only reduce logs.
        boolean shouldLog = (PI_DEBUG_LIMIT-- > 0);

        // Defensive extraction: these helpers avoid crashes if any field is missing/null.
        InteractionType type = safeInteractionType(event);
        String uuid = safeUuid(event);
        String itemInHand = safeItemInHandId(event);
        Object chain = safeInteractionChain(event);

        // Step 1: resolve (world,x,y,z) of the interacted block.
        // Prefer extracting from the interaction chain (if it contains a hit result).
        // If that fails, use our per-tick look tracker (fresh aim data).
        BlockLocation loc = null;
        LookTarget look = null;

        try {
            loc = tryExtractBlockLocation(uuid, event, chain);

            // Fallback: if the chain doesn't provide a usable position, use "what the player was looking at".
            if (loc == null) {
                look = getFreshLookTarget(uuid);
                if (look != null && look.world != null && look.basePos != null) {
                    loc = new BlockLocation(look.world, look.basePos.x, look.basePos.y, look.basePos.z);
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-PI] Failed while extracting block location");
        }

        // If we used look-tracker fallback, log it once per interaction (not per tick).
        if (look != null && look.basePos != null) {
            long ageMs = (System.nanoTime() - look.nanoTime) / 1_000_000L;
            if (shouldLog) {
                LOGGER.atInfo().log("[RR-LOOK] uuid=%s lookBlock=%s pos=%d,%d,%d ageMs=%d",
                        uuid,
                        String.valueOf(look.blockId),
                        look.basePos.x, look.basePos.y, look.basePos.z,
                        ageMs);
            }
        }

        // Log the interaction summary (bounded by shouldLog).
        if (shouldLog) {
            LOGGER.atInfo().log("[RR-PI] uuid=%s type=%s item=%s chain=%s pos=%s",
                    uuid,
                    String.valueOf(type),
                    itemInHand,
                    (chain == null ? "<null>" : chain.getClass().getName()),
                    (loc == null ? "<none>" : (loc.x + "," + loc.y + "," + loc.z)));
        }

        // If we can't determine a valid location, we can't safely change the world.
        if (loc == null || loc.world == null) return;

        // Step 2: confirm what block is actually at that position.
        // We intentionally read the block ID from the world so we don't swap the wrong thing.
        String clickedId = tryGetBlockIdAt(loc.world, loc.x, loc.y, loc.z);
        if (clickedId == null) return;

        // Only continue if this is one of our stand variants.
        if (!isStandId(clickedId)) return;

        // Step 3: choose the desired stand variant.
        String desiredStand;
        if (PHASE1_TOGGLE_BLUE_ONLY) {
            // Phase 1 behavior: toggle between empty and blue.
            desiredStand = STAND_BLUE.equals(clickedId) ? STAND_EMPTY : STAND_BLUE;
        } else {
            // Phase 2+ behavior: choose stand based on what flag item is currently in hand.
            desiredStand = STAND_EMPTY;
            if (FLAG_RED.equals(itemInHand)) desiredStand = STAND_RED;
            else if (FLAG_BLUE.equals(itemInHand)) desiredStand = STAND_BLUE;
            else if (FLAG_WHITE.equals(itemInHand)) desiredStand = STAND_WHITE;
            else if (FLAG_YELLOW.equals(itemInHand)) desiredStand = STAND_YELLOW;
        }

        // (The remainder of your method continues here: doing the swap / dry-run using desiredStand)




        if (!ENABLE_STAND_SWAP) {
            LOGGER.atInfo().log("[RR] (dry-run) would swap stand @ %d,%d,%d from %s -> %s",
                    loc.x, loc.y, loc.z, clickedId, desiredStand);
            return;
        }

        setStandBlock(loc, desiredStand);
    }

    // -----------------------------------------------------------------------------
// Defensive getters for PlayerInteractLib events
// -----------------------------------------------------------------------------
//
// These "safeXxx" methods are intentionally repetitive and over-defensive.
//
// Why so defensive?
// - We are integrating with an external plugin (PlayerInteractLib) via reflection.
// - Different server/plugin builds may expose fields as:
//      record accessors: e.uuid(), e.itemInHandId(), ...
//      getters:          e.getUuid(), e.getItemInHandId(), ...
//      or different naming entirely.
// - We want the plugin to keep running even if one method name changes.
//
// Pattern used:
//  1) Try the "ideal" direct accessor (fast path)
//  2) Try reflection-based access with a couple known method names
//  3) Fall back to a safe default
//
// This keeps the mod resilient to version drift.

    private InteractionType safeInteractionType(PlayerInteractionEvent e) {
        // Fast path: record accessor (most likely)
        try { return e.interactionType(); } catch (Throwable ignored) {}

        // Compatibility path: getter/alt accessor
        try {
            Object v = safeCall(e, "getInteractionType", "interactionType");
            if (v instanceof InteractionType it) return it;

            // Sometimes libraries return an enum-like value that prints the same name
            // as our InteractionType. We try to map by name.
            if (v != null) return InteractionType.valueOf(String.valueOf(v));
        } catch (Throwable ignored) {}

        // Null means "unknown interaction type"
        return null;
    }

    private String safeUuid(PlayerInteractionEvent e) {
        // Fast path: record accessor
        try { return e.uuid(); } catch (Throwable ignored) {}

        // Compatibility path
        try {
            Object v = safeCall(e, "getUuid", "uuid");
            return v == null ? "<null>" : String.valueOf(v);
        } catch (Throwable ignored) {}

        return "<null>";
    }

    private String safeItemInHandId(PlayerInteractionEvent e) {
        // Fast path: record accessor
        try { return e.itemInHandId(); } catch (Throwable ignored) {}

        // Compatibility path
        try {
            Object v = safeCall(e, "getItemInHandId", "itemInHandId");
            return v == null ? "<empty>" : String.valueOf(v);
        } catch (Throwable ignored) {}

        return "<empty>";
    }

    private Object safeInteractionChain(PlayerInteractionEvent e) {
        // Fast path: record accessor
        try { return e.interaction(); } catch (Throwable ignored) {}

        // Compatibility path
        try { return safeCall(e, "getInteraction", "interaction"); } catch (Throwable ignored) {}

        return null;
    }



// -----------------------------------------------------------------------------
// Look tracker helpers (EyeSpy approach)
// -----------------------------------------------------------------------------
//
// The look tracker runs every tick and stores "what block is this player looking at?".
// We only want to use that data if it's *fresh*, otherwise we might swap the wrong block
// if the player moved their aim.
//
// Freshness check concept:
// - we accept a look target only if it was recorded in the last LOOK_FRESH_NANOS (250ms).
// - 250ms is short enough to match the moment of interaction, but long enough to handle
//   slight timing differences between tick updates and interaction events.

    private LookTarget getFreshLookTarget(String uuid) {
        // Guard against garbage keys (uuid sometimes becomes "<null>" from safeUuid)
        if (uuid == null || uuid.isEmpty() || "<null>".equals(uuid)) return null;

        LookTarget t = lookByUuid.get(uuid);
        if (t == null) return null;

        long age = System.nanoTime() - t.nanoTime;
        if (age > LOOK_FRESH_NANOS) return null;

        return t;
    }

    /**
     * Convenience: take a UUID and return a BlockLocation directly from the look tracker.
     *
     * NOTE: This is currently a helper used as a fallback inside tryExtractBlockLocation.
     * If you later restructure tryExtractBlockLocation, this helper might become unused.
     */
    private BlockLocation tryLocationFromLook(String uuid) {
        if (uuid == null || uuid.isEmpty() || "<null>".equals(uuid)) return null;

        LookTarget t = getFreshLookTarget(uuid);
        if (t == null) return null;

        if (t.world == null || t.basePos == null) return null;

        return new BlockLocation(t.world, t.basePos.x, t.basePos.y, t.basePos.z);
    }


// -----------------------------------------------------------------------------
// Location extraction: turning "player interacted" into "world + x,y,z"
// -----------------------------------------------------------------------------
//
// This is one of the most important parts of the mod.
//
// Inputs:
// - uuid: identifies the player (used for look-tracker map lookup)
// - event: the PlayerInteractLib event
// - chain: "SyncInteractionChain" (or similar) which sometimes contains hit/target info
//
// Output:
// - BlockLocation containing world + x,y,z (or null if we cannot determine target)
//
// Extraction strategy:
//  1) Try to pull World directly off event (if exposed by build)
//  2) Recursively search inside "chain" for objects that contain x/y/z
//  3) Fallback to look tracker (uuid -> looked-at block)
//  4) Fallback to remembered UseBlockEvent location (rare but sometimes helpful)
//
// This is deliberately layered: each fallback is less "direct" than the previous.

    private BlockLocation tryExtractBlockLocation(String uuid, PlayerInteractionEvent event, Object chain) {
        World world = null;

        // Some builds may expose the World directly on the event.
        // If we can find it, it makes any extracted x/y/z more reliable.
        try {
            Object w = safeCall(event, "getWorld", "world");
            if (w instanceof World ww) world = ww;
        } catch (Throwable ignored) {}

        // 1) Attempt to drill into the interaction chain.
        // The chain may contain nested objects such as hit results / target data.
        BlockLocation found = extractPosRecursive(chain, world, 0, "chain");
        if (found != null) return found;

        // 2) Fallback: looked-at block from EyeSpy raycast (fresh within 250ms).
        BlockLocation lookLoc = tryLocationFromLook(uuid);
        if (lookLoc != null) return lookLoc;

        // 3) Fallback: if legacy UseBlock ever captured a target location, use it.
        // This is a "best effort" and may be stale, so we keep it last.
        if (pendingStandLocation != null) {
            // If pendingStandLocation includes a world, prefer it.
            if (pendingStandLocation.world != null) return pendingStandLocation;

            // If it doesn't, but we found a world from the event, combine them.
            if (world != null) return new BlockLocation(world, pendingStandLocation.x, pendingStandLocation.y, pendingStandLocation.z);
        }

        return null;
    }


// -----------------------------------------------------------------------------
// Recursive reflection search for position data
// -----------------------------------------------------------------------------
//
// This is the "spelunker": it explores nested objects looking for something that looks like
// a coordinate triplet (x,y,z) or (blockX,blockY,blockZ), and optionally a World.
//
// How it works:
// - First, it tries tryReadXYZ(obj) to see if obj itself exposes coordinates.
// - If not, it optionally dumps method/field names one time per class (debug discovery).
// - Then it scans methods and fields whose NAMES contain hints like "hit", "target", "pos", etc.
// - It recurses into those children until it finds coordinates or exceeds depth.
//
// Why limit depth?
// - Prevents runaway recursion on huge graphs.
// - Makes the search predictable and safer for runtime.

    private BlockLocation extractPosRecursive(Object obj, World world, int depth, String path) {
        if (obj == null) return null;

        // Depth limit is a safety rail.
        // If you later discover position is nested deeper, you can increase this to 5 or 6,
        // but keep a limit to avoid exploring entire object graphs.
        if (depth > 4) return null;

        // Step 1: Does this object directly expose x/y/z?
        BlockLocation direct = tryReadXYZ(obj, world);
        if (direct != null) {
            LOGGER.atInfo().log("[RR-PI] FOUND pos via %s (%s)", path, obj.getClass().getName());
            return direct;
        }

        // Step 2: One-time "what does this object contain?" dump.
        // This is development-time introspection. Great while reverse engineering.
        // You can gate it behind a DEBUG flag later if desired.
        Class<?> cls = obj.getClass();
        if (dumpedInteractionClasses.add(cls)) {
            dumpInteractionSurface(obj);
        }

        // Names that often appear in hit/target/position structures.
        // We only recurse into members with these keywords to keep search focused.
        String[] keys = new String[] { "hit", "target", "block", "pos", "position", "location", "coord", "world", "chunk" };

        // Step 3: Scan methods first (public getters are often more stable than fields).
        for (Method m : cls.getMethods()) {
            try {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == Void.TYPE) continue;

                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!containsAny(name, keys)) continue;

                Object child = m.invoke(obj);
                if (child == null) continue;

                // Skip "leafy" values that are not useful for recursion
                if (child instanceof String) continue;
                if (child.getClass().isPrimitive()) continue;
                if (child instanceof Number) continue;

                // If this child is a World, capture it for downstream coordinate reads.
                if (world == null && child instanceof World ww) world = ww;

                BlockLocation loc = extractPosRecursive(child, world, depth + 1, path + "." + m.getName() + "()");
                if (loc != null) return loc;
            } catch (Throwable ignored) {}
        }

        // Step 4: If methods didn't work, scan declared fields (less stable, but sometimes necessary).
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


// -----------------------------------------------------------------------------
// Coordinate reading helpers
// -----------------------------------------------------------------------------
//
// tryReadXYZ attempts common coordinate “shapes” seen across libraries.
// If the object exposes x/y/z, we build a BlockLocation.

    private BlockLocation tryReadXYZ(Object obj, World world) {
        try {
            // Common getter shapes: getX()/getY()/getZ() OR x()/y()/z()
            Integer x = tryGetInt(obj, "getX", "x");
            Integer y = tryGetInt(obj, "getY", "y");
            Integer z = tryGetInt(obj, "getZ", "z");
            if (x != null && y != null && z != null) return new BlockLocation(world, x, y, z);

            // Alternative shapes: getBlockX()/blockX() or field blockX, etc.
            x = tryGetInt(obj, "getBlockX", "blockX");
            y = tryGetInt(obj, "getBlockY", "blockY");
            z = tryGetInt(obj, "getBlockZ", "blockZ");
            if (x != null && y != null && z != null) return new BlockLocation(world, x, y, z);
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Reads an integer property from an object by trying:
     * - a named getter method (getX)
     * - a named no-arg method (x)
     * - a public field (x)
     * - a private/protected field (x)
     *
     * Why so many attempts?
     * - Different internal classes follow different conventions.
     * - Reflection lets us survive API shape differences.
     */
    private Integer tryGetInt(Object obj, String getterName, String fieldName) {
        // Getter method: getX()
        try {
            Method m = obj.getClass().getMethod(getterName);
            if (m.getParameterCount() == 0) {
                Object v = m.invoke(obj);
                if (v instanceof Number n) return n.intValue();
            }
        } catch (Throwable ignored) {}

        // No-arg method: x()
        try {
            Method m = obj.getClass().getMethod(fieldName);
            if (m.getParameterCount() == 0) {
                Object v = m.invoke(obj);
                if (v instanceof Number n) return n.intValue();
            }
        } catch (Throwable ignored) {}

        // Public field: obj.x
        try {
            var f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        // Declared field: private/protected x
        try {
            var f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Simple keyword filter used by extractPosRecursive.
     * We pass the method/field name already lowercased to avoid repeated allocations.
     */
    private boolean containsAny(String haystackLower, String[] needlesLower) {
        for (String n : needlesLower) {
            if (haystackLower.contains(n)) return true;
        }
        return false;
    }

    /**
     * Reflection-based “surface dump” used during reverse engineering.
     *
     * What it does:
     * - Logs method names and field names that look relevant to positions and hits.
     * - Only runs once per class (guarded by dumpedInteractionClasses).
     *
     * Why keep it:
     * - When Hytale/PlayerInteractLib internals change, you can quickly rediscover the new
     *   path to the coordinates by inspecting what members exist.
     *
     * If you want less log noise:
     * - add a DEBUG_REFLECTION_DUMPS flag and wrap this call.
     */
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


// -----------------------------------------------------------------------------
// Domain checks: is this block one of our stand variants?
// -----------------------------------------------------------------------------

    private boolean isStandId(String id) {
        // Using direct equality keeps it obvious and fast.
        // If this list grows, we can convert it into a Set<String>.
        return STAND_EMPTY.equals(id) ||
                STAND_RED.equals(id) ||
                STAND_BLUE.equals(id) ||
                STAND_WHITE.equals(id) ||
                STAND_YELLOW.equals(id);
    }


// -----------------------------------------------------------------------------
// World read helper: ask the World what block is at (x,y,z)
// -----------------------------------------------------------------------------
//
// Why reflection here?
// - Different server builds may expose "getBlockType" vs "getBlock" or other variants.
// - We attempt common shapes to remain compatible.
//
// If this method returns null:
// - we couldn't find a compatible getter OR the invocation failed.

    private String tryGetBlockIdAt(World world, int x, int y, int z) {
        try {
            Method m = null;

            // Try common signature: getBlockType(int,int,int)
            try { m = world.getClass().getMethod("getBlockType", int.class, int.class, int.class); } catch (Throwable ignored) {}

            // Fallback common signature: getBlock(int,int,int)
            if (m == null) {
                try { m = world.getClass().getMethod("getBlock", int.class, int.class, int.class); } catch (Throwable ignored) {}
            }

            if (m == null) return null;

            Object bt = m.invoke(world, x, y, z);
            if (bt instanceof BlockType blockType) {
                return safeBlockTypeId(blockType);
            }

            // If bt isn't a BlockType, we still return a string representation for debugging.
            return String.valueOf(bt);
        } catch (Throwable ignored) {
            return null;
        }
    }


// ---------- Block placement ----------
//
// This is the "write" side: it performs the actual world edit.
// WARNING (gameplay/design note):
// - If this block has an attached inventory (block entity), swapping block IDs may reset it.
// - You're okay with that in early phases, but keep it in mind when you later implement deposit logic.

    private void setStandBlock(BlockLocation loc, String standKey) {
        if (loc == null || loc.world == null) return;

        // Convert the block asset ID string to the internal numeric ID used by the engine.
        int newBlockId = BlockType.getAssetMap().getIndex(standKey);

        // getIndex returns Integer.MIN_VALUE when the key is not in the AssetMap.
        // We warn once per missing key so logs don't flood.
        if (newBlockId == Integer.MIN_VALUE) {
            if (warnedMissingStandKeys.add(standKey)) {
                LOGGER.atWarning().log("[RR] stand asset id not found for %s", standKey);
            }
            return;
        }

        // Worlds are chunked. We must load the chunk containing this block before editing.
        long chunkIndex = ChunkUtil.indexChunkFromBlock(loc.x, loc.z);
        WorldChunk chunk = loc.world.getChunk(chunkIndex);

        // If chunk isn't loaded, we skip the swap. This avoids null pointer errors.
        if (chunk == null) {
            if (warnedMissingChunkIndices.add(chunkIndex)) {
                LOGGER.atWarning().log("[RR] chunk not loaded for swap at %d,%d,%d (chunkIndex=%d)",
                        loc.x, loc.y, loc.z, chunkIndex);
            }
            return;
        }

        // The final write: place the new block ID at the location.
        // The final parameter (0) is currently an "options/flags" value.
        // If later you need neighbor updates or physics triggers, this flag may matter.
        chunk.setBlock(loc.x, loc.y, loc.z, newBlockId, 0);

        LOGGER.atInfo().log("[RR] Swapped stand @ %d,%d,%d -> %s", loc.x, loc.y, loc.z, standKey);
    }


// ---------- Helpers ----------
//
// UseBlockEvent-based fallback position capture.
// Useful when UseBlockEvent actually fires for stands or when debugging target resolution.

    private void rememberStandLocationFromUseBlock(UseBlockEvent.Pre event, InteractionContext ctx) {
        try {
            World world = null;

            // Try to get world from event first
            try {
                Method m = event.getClass().getMethod("getWorld");
                Object w = m.invoke(event);
                if (w instanceof World ww) world = ww;
            } catch (Throwable ignored) {}

            // Fallback: try to get world from context
            if (world == null) {
                try {
                    Method m = ctx.getClass().getMethod("getWorld");
                    Object w = m.invoke(ctx);
                    if (w instanceof World ww) world = ww;
                } catch (Throwable ignored) {}
            }

            if (world == null) return;

            // Try to get position object (naming can vary by build)
            Object pos = safeCall(event, "getTargetBlock", "targetBlock");
            if (pos == null) return;

            // Read x/y/z out of that position object using reflection-based helpers
            int x = readVecInt(pos, "getX", "x");
            int y = readVecInt(pos, "getY", "y");
            int z = readVecInt(pos, "getZ", "z");

            pendingStandLocation = new BlockLocation(world, x, y, z);
        } catch (Throwable ignored) {}
    }

    /**
     * Safe "try these method names" helper used throughout the reflection-based compatibility code.
     * NOTE: This is heavily used, do not remove.
     */
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

    /**
     * NOTE: Currently appears unused in the shown snippet (you mostly use safeInteractionType now).
     * Keep it if it is used elsewhere; otherwise it can be removed later.
     *
     * Purpose:
     * - Converts a reflected value into our InteractionType enum by name.
     */
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


    // -----------------------------------------------------------------------------
// Small reflection utilities (vector/item/block id extraction + messaging)
// -----------------------------------------------------------------------------
//
// These helpers exist because the Hytale server/plugin APIs vary across builds.
// Some objects expose properties via getters, others via fields, and some only via toString().
// We use "best effort" extraction to prevent crashes and keep the mod robust.

    // Reads an integer component from a "vector-like" object.
// Example: a position object that might have getX() or a public field x.
//
// NOTE: This returns 0 if no value is found.
// - That is safe for "best effort" fallbacks, but be aware it can hide failures.
// - If you ever debug "why are positions sometimes 0,0,0", this method is a suspect.
    private int readVecInt(Object vec, String getterName, String fieldName) {
        // Attempt 1: getter method, e.g. getX()
        try {
            Method m = vec.getClass().getMethod(getterName);
            Object v = m.invoke(vec);
            if (v instanceof Integer i) return i;
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        // Attempt 2: public field, e.g. x
        try {
            var f = vec.getClass().getField(fieldName);
            Object v = f.get(vec);
            if (v instanceof Integer i) return i;
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}

        // Fallback: unable to read the value
        return 0;
    }

    /**
     * Reads an Item ID from an unknown object that might represent an item stack.
     *
     * Why this exists:
     * - In some events/contexts we receive ItemStack
     * - In others we might receive a different "item-like" wrapper with getItemId() or itemId()
     * - This keeps our logging and decision logic consistent across those shapes.
     *
     * Returns:
     * - "<empty>" if null
     * - otherwise best-effort string ID
     *
     * NOTE: Used mainly for logging/inspection; your main logic uses safeItemId(ItemStack) when possible.
     */
    private String safeItemIdFromUnknown(Object maybeItemStack) {
        if (maybeItemStack == null) return "<empty>";

        // If this is already an ItemStack, use the canonical extraction helper
        try {
            if (maybeItemStack instanceof ItemStack is) return safeItemId(is);
        } catch (Throwable ignored) {}

        // Try common method name: getItemId()
        try {
            Method m = maybeItemStack.getClass().getMethod("getItemId");
            Object v = m.invoke(maybeItemStack);
            return String.valueOf(v);
        } catch (Throwable ignored) {}

        // Try alternate method name: itemId()
        try {
            Method m = maybeItemStack.getClass().getMethod("itemId");
            Object v = m.invoke(maybeItemStack);
            return String.valueOf(v);
        } catch (Throwable ignored) {}

        // Last resort: string representation (useful for debugging even if not stable)
        return String.valueOf(maybeItemStack);
    }

    /**
     * Extract a stable string ID from a BlockType.
     *
     * We try:
     * - getId() via reflection (most stable)
     * - fallback to toString()
     *
     * NOTE: This is used both for logging and for verifying that the block at (x,y,z) is a stand.
     */
    private String safeBlockTypeId(BlockType blockType) {
        try {
            Object v = blockType.getClass().getMethod("getId").invoke(blockType);
            return String.valueOf(v);
        } catch (Exception e) {
            // Some builds might not expose getId(); toString() is a fallback.
            return blockType.toString();
        }
    }

    /**
     * Extract the item ID string from an ItemStack.
     *
     * This is the canonical way we want to identify an item in hand.
     * If this fails (rare), toString() provides a debugging fallback.
     */
    private String safeItemId(ItemStack stack) {
        try {
            return String.valueOf(stack.getItemId());
        } catch (Throwable t) {
            return stack.toString();
        }
    }

    /**
     * Sends an in-game message to the player associated with an InteractionContext.
     *
     * Why this helper exists:
     * - A fast sanity check while testing (without tailing logs).
     * - It safely navigates the ECS-style component system.
     *
     * NOTE: This is best-effort and intentionally silent on failure.
     * If you ever need to debug messaging, we can add log output behind a debug flag.
     */
    private void sendPlayerMessage(InteractionContext ctx, Message msg) {
        try {
            // CommandBuffer is used to access components for the interaction entity.
            if (ctx.getCommandBuffer() == null) return;

            // Ensure entity is valid before reading components from it.
            if (ctx.getEntity() == null || !ctx.getEntity().isValid()) return;

            // Pull the Player component off the ECS entity and send message.
            Player player = (Player) ctx.getCommandBuffer().getComponent(ctx.getEntity(), Player.getComponentType());
            if (player != null) player.sendMessage(msg);
        } catch (Exception ignored) {}
    }


// -----------------------------------------------------------------------------
// Look target tracking (EyeSpy technique)
// -----------------------------------------------------------------------------
//
// This is the "sensor" system that runs every tick.
// It answers: "What block is each player currently aiming at?"
//
// We store it keyed by UUID so we can join with PlayerInteractLib interaction events.
//
// Why we store both targetPos and basePos:
// - targetPos is the raw raycast hit
// - basePos resolves "filler blocks" (multi-block structures) to the true base block
//   so that swapping the stand will affect the correct placed block.

    /**
     * Immutable data snapshot of what a player is looking at at a moment in time.
     */
    private static final class LookTarget {
        final World world;

        /** Raw raycast hit position (can be a filler part of a larger block structure). */
        final Vector3i targetPos;

        /** Resolved position (adjusted for filler blocks so we target the base block). */
        final Vector3i basePos;

        /** Best-effort string ID for the block type at basePos (used for debugging and safety checks). */
        final String blockId;

        /** Timestamp used to enforce freshness (we only trust recent aim data). */
        final long nanoTime;

        LookTarget(World world, Vector3i targetPos, Vector3i basePos, String blockId, long nanoTime) {
            this.world = world;
            this.targetPos = targetPos;
            this.basePos = basePos;
            this.blockId = blockId;
            this.nanoTime = nanoTime;
        }
    }

    /**
     * EntityTickingSystem that updates lookByUuid every tick.
     *
     * Runs for every entity matching the query (players).
     * IMPORTANT: This tick method runs extremely frequently, so:
     * - it must be fast
     * - it should avoid heavy logging
     * - it should swallow errors (or gate logs behind a debug flag)
     */
    private final class LookTargetTrackerSystem extends EntityTickingSystem<EntityStore> {

        /**
         * Query determines which ECS entities this system runs for.
         * We require both Player and PlayerRef components, because:
         * - Player gives us identity/connection info
         * - PlayerRef tends to contain UUID and is a stable reference
         */
        private final Query<EntityStore> query;

        LookTargetTrackerSystem() {
            this.query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());
        }

        /**
         * Engine callback: the system must declare what it wants to tick.
         * This method is required even if it feels "unused" because the ECS framework calls it.
         */
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        /**
         * Per-tick update for each player entity.
         *
         * Steps:
         *  1) Resolve Player + PlayerRef components
         *  2) Extract player's UUID (string)
         *  3) Raycast to find the targeted block within LOOK_RAYCAST_RANGE
         *  4) Resolve filler/base block position
         *  5) Read block type at that base position
         *  6) Store in lookByUuid map for later use by interaction handler
         */
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

                // ExternalData contains world info for this store (engine-specific wiring).
                EntityStore es = (EntityStore) store.getExternalData();
                if (es == null) return;

                World world = es.getWorld();
                if (world == null) return;

                // Resolve correct chunk + filler blocks (same logic as EyeSpy BlockContext.create)
                long hitChunkIndex = ChunkUtil.indexChunkFromBlock(hit.x, hit.z);
                WorldChunk hitChunk = world.getChunkIfLoaded(hitChunkIndex);
                if (hitChunk == null) return;

                // Convert hit position to base block position (handles filler blocks).
                Vector3i base = resolveBaseBlock(hitChunk, hit.x, hit.y, hit.z);
                if (base == null) return;

                // Base block might be in a different chunk.
                long baseChunkIndex = ChunkUtil.indexChunkFromBlock(base.x, base.z);
                WorldChunk baseChunk = (baseChunkIndex == hitChunkIndex)
                        ? hitChunk
                        : world.getChunkIfLoaded(baseChunkIndex);

                if (baseChunk == null) return;

                // Read block type at base position
                BlockType bt = baseChunk.getBlockType(base.x, base.y, base.z);
                String blockId = (bt == null) ? null : safeBlockTypeId(bt);

                // Store a fresh snapshot keyed by UUID.
                // Note: we store world as well so interaction handler can directly act on it.
                lookByUuid.put(uuid, new LookTarget(world, hit, base, blockId, System.nanoTime()));

            } catch (Throwable ignored) {
                // Intentionally silent: this runs every tick.
                // If you ever debug issues here, add a DEBUG flag and log occasionally.
            }
        }
    }

    /**
     * Convert a potentially "filler block" hit into the true base block position.
     *
     * Background:
     * - Some complex blocks are represented as a base block + filler parts.
     * - The engine stores filler offsets packed into an int via FillerBlockUtil.
     * - To modify the real block, we must subtract those offsets.
     *
     * NOTE: chunk.getFiller(...) is deprecated in your build, but still works.
     * When it is removed in the future, we will need the new API equivalent.
     */
    private static Vector3i resolveBaseBlock(WorldChunk chunk, int x, int y, int z) {
        int filler = chunk.getFiller(x, y, z);
        if (filler == 0) return new Vector3i(x, y, z);

        return new Vector3i(
                x - FillerBlockUtil.unpackX(filler),
                y - FillerBlockUtil.unpackY(filler),
                z - FillerBlockUtil.unpackZ(filler)
        );
    }


// -----------------------------------------------------------------------------
// UUID extraction helpers (PlayerRef / Player / regex fallback)
// -----------------------------------------------------------------------------
//
// UUID extraction is surprisingly annoying across versions, so we use best-effort methods.
// This is only used to key our lookByUuid map.

    private String safeUuidFromPlayer(PlayerRef ref, Player player) {
        // Prefer PlayerRef methods (most stable), then Player methods, then toString() fallback.
        String u = safeUuidFromObject(ref);
        if (u != null) return u;

        u = safeUuidFromObject(player);
        if (u != null) return u;

        // Last resort: regex search UUID inside toString() output.
        // This is not ideal but can save you if method names change.
        try {
            String s = String.valueOf(ref);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
                    .matcher(s);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Best-effort UUID/ID extraction from an arbitrary object via common method names.
     *
     * NOTE:
     * - We treat any returned string length >= 32 as "probably an ID/UUID".
     * - If you want stricter behavior later, we can regex validate a UUID pattern here.
     */
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

                // Heuristic: UUIDs and unique IDs are generally long strings.
                if (s.length() >= 32) return s;
            } catch (Throwable ignored) {}
        }

        return null;
    }


// -----------------------------------------------------------------------------
// BlockLocation: small internal struct used by swap logic
// -----------------------------------------------------------------------------
//
// This is intentionally minimal.
// It allows us to represent:
// - a fully resolved location (world + x,y,z)
// - OR coordinates alone when world isn't known yet (world=null)

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