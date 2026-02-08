package com.Chris__.realm_ruler;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.Chris__.realm_ruler.core.ModeManager;
import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.Chris__.realm_ruler.modes.CtfMode;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.world.StandSwapService;
import com.Chris__.realm_ruler.targeting.TargetingModels;
import com.Chris__.realm_ruler.targeting.TargetingModels.BlockLocation;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.Chris__.realm_ruler.platform.PlayerInteractAdapter;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.match.CtfAutoRespawnAndTeleportSystem;
import com.Chris__.realm_ruler.match.CtfCarrierDropBlockSystem;
import com.Chris__.realm_ruler.match.CtfCarrierSlotLockSystem;
import com.Chris__.realm_ruler.match.CtfFlagStateService;
import com.Chris__.realm_ruler.match.CtfMatchEndService;
import com.Chris__.realm_ruler.match.CtfPointsRepository;
import com.Chris__.realm_ruler.match.CtfShopConfigRepository;
import com.Chris__.realm_ruler.match.CtfStandRegistryRepository;
import com.Chris__.realm_ruler.integration.MultipleHudBridge;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.npc.NpcArenaRepository;
import com.Chris__.realm_ruler.npc.NpcLifecycleSystem;
import com.Chris__.realm_ruler.npc.NpcSpawnAdapter;
import com.Chris__.realm_ruler.npc.NpcSpawnAdapterCommandBridge;
import com.Chris__.realm_ruler.npc.NpcSpawnAdapterFallback;
import com.Chris__.realm_ruler.npc.NpcTestService;
import com.Chris__.realm_ruler.ui.pages.ctf.CtfShopUiService;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.Flow;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * Realm Ruler: quick navigation + runtime flow map
 * Keep this directly under imports so it stays “always visible”.
 * ─────────────────────────────────────────────────────────────────────────────
 * WHAT TO EDIT WHERE (authority map)
 *   - Wiring / plugin boot / subscriptions:     Realm_Ruler.java
 *   - Mode routing / active mode selection:     core/ModeManager.java
 *   - CTF behavior (interactions):              modes/CtfMode.java
 *   - CTF rules + IDs (pure logic):             modes/ctf/CtfRules.java
 *   - CTF stored state (stand->flag):           modes/ctf/CtfState.java
 *   - Target resolution + look tracking:        targeting/TargetingService.java
 *   - Shared targeting models:                  targeting/TargetingModels.java
 *   - World write boundary (stand swaps):       world/StandSwapService.java
 *   - PlayerInteractLib plumbing/adapters:      platform/...
 * RUNTIME FLOW (high level)
 *   INPUTS
 *     1) PlayerInteractLib (primary): PlayerInteractionEvent (often InteractionType.Use for “F/use”)
 *        Realm_Ruler.tryRegisterPlayerInteractLib()
 *          -> subscriber.onNext(...) -> Realm_Ruler.onPlayerInteraction(event)
 *     2) Hytale UseBlockEvent.Pre (fallback telemetry / position hint)
 *        Realm_Ruler.setup() registers when ENABLE_USEBLOCK_FALLBACK=true
 *          -> Realm_Ruler.onUseBlock(event)
 *          -> TargetingService.rememberPendingStandLocation(...) (optional hint)
 *   DISPATCH
 *     ModeManager.dispatchPlayerAction(action)
 *       action is either PlayerInteractionEvent or UseBlockEvent.Pre
 *       -> modes/CtfMode.onPlayerAction(action)
 *   WHERE RESOLUTION (action -> World + x,y,z)
 *     TargetingService.resolveTarget(uuid, event, chain)
 *       1) CHAIN: extractPosRecursive(chain, world, ...)
 *       2) LOOK : getFreshLookTarget(uuid) (freshness-gated)
 *       3) USEBLOCK: pendingStandLocation fallback
 *   TICK-THREAD SAFETY (required for world/inventory writes)
 *     Realm_Ruler.rrRunOnTick(Runnable) -> tickQueue
 *     tickQueue drained on tick thread by TargetingService.LookTargetTrackerSystem.tick(...)
 *   WORLD WRITE BOUNDARY
 *     StandSwapService.swapStand(world, x, y, z, desiredStandId)
 *       - validates asset id exists
 *       - checks chunk loaded
 *       - performs the block swap
 * THREADING RED FLAGS
 *   - Any inventory mutation must happen on tick thread.
 *   - Any world write (stand swap) must happen on tick thread.
 *   - If you add a new path that edits inventory or swaps blocks: wrap it in rrRunOnTick(...).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 */


public class Realm_Ruler extends JavaPlugin {


    private ModeManager modeManager;
    private CtfMode ctfMode;
    private final StandSwapService standSwapService = new StandSwapService(LOGGER);
    private TargetingService targetingService;
    private CtfMatchService ctfMatchService;
    private SimpleClaimsCtfBridge simpleClaimsCtfBridge;
    private CtfFlagStateService ctfFlagStateService;
    private CtfStandRegistryRepository ctfStandRegistryRepository;
    private CtfPointsRepository ctfPointsRepository;
    private CtfMatchEndService ctfMatchEndService;
    private CtfShopConfigRepository ctfShopConfigRepository;
    private CtfShopUiService ctfShopUiService;
    private NpcArenaRepository npcArenaRepository;
    private NpcTestService npcTestService;
    private MultipleHudBridge multipleHudBridge;
    private final PlayerInteractAdapter pi = new PlayerInteractAdapter();

    private void setupModes() {
        modeManager = new ModeManager();

        ctfMode = new CtfMode(this);
        modeManager.register(ctfMode);

        modeManager.setActive("ctf");
    }

    public String rrWorldKey(World world) {
        if (world == null) return "<world?>";

        // Try common identifiers via reflection (API drift tolerant).
        Object v = safeCall(world, "getId", "id", "getName", "name", "getKey", "key", "getWorldId", "worldId");
        if (v != null) {
            String s = String.valueOf(v);
            if (!s.isBlank()) return s;
        }

        // Fallback: stable enough within a running server process.
        return "world@" + System.identityHashCode(world);
    }


    /** Logger provided by the Hytale server runtime. */
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // These toggles exist to help isolate bugs and reduce noise while testing.
    // They should NOT change core gameplay logic, only enable/disable optional telemetry/fallbacks.
    private static final boolean ENABLE_USEBLOCK_FALLBACK = true;
    private static final boolean ENABLE_LOOK_TRACKER = true;


    // TICK-SAFE EXECUTOR: queue work from async callbacks to run on tick thread.
    private final ConcurrentLinkedQueue<Runnable> tickQueue = new ConcurrentLinkedQueue<>();

    // PLAYER RESOLUTION: uuid -> Player (refreshed every tick by LookTargetTrackerSystem)
    public final Map<String, Player> playerByUuid = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Asset IDs (strings must match your JSON block/item IDs exactly)
    // -------------------------------------------------------------------------

    /** Stand block IDs (placed in the world). */
    private static final String STAND_EMPTY  = "Flag_Stand";
    private static final String STAND_RED    = "Flag_Stand_Red";
    private static final String STAND_BLUE   = "Flag_Stand_Blue";
    private static final String STAND_WHITE  = "Flag_Stand_White";
    private static final String STAND_YELLOW = "Flag_Stand_Yellow";

    /** Small in-game message used for quick sanity testing. */
    private static final Message MSG_DEBUG_HIT =
            Message.raw("[RealmRuler] Flag stand interaction detected.");

    //Limits how many UseBlock fallback logs we print (UseBlockEvent can be noisy when enabled).
    private static int USEBLOCK_DEBUG_LIMIT = 30;

    public Realm_Ruler(@Nonnull JavaPluginInit init) {
        super(init);
        setupModes();
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());

        // Debug: dump Player/Inventory API methods once at startup.
        if (rrDebug()) {
            dumpPlayerMethods();
            dumpInventoryApis();
        }

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
        this.multipleHudBridge = new MultipleHudBridge(LOGGER);
        try {
            this.multipleHudBridge.requireReadyOrThrow();
        } catch (IllegalStateException e) {
            LOGGER.atSevere().withCause(e).log("[RR-HUD] MultipleHUD integration failed. Aborting setup.");
            throw e;
        }

        this.targetingService = new TargetingService(LOGGER, tickQueue, playerByUuid, this.multipleHudBridge);
        this.ctfMatchService = new CtfMatchService(this.targetingService, this.ctfMode);
        this.targetingService.setLobbyHudStateProvider(this.ctfMatchService::lobbyHudStateFor);
        this.simpleClaimsCtfBridge = new SimpleClaimsCtfBridge(LOGGER);
        this.ctfStandRegistryRepository = new CtfStandRegistryRepository(this.getDataDirectory(), LOGGER);
        this.ctfFlagStateService = new CtfFlagStateService(
                this.ctfMatchService,
                this.simpleClaimsCtfBridge,
                this.ctfStandRegistryRepository,
                this::rrCreateItemStackById,
                LOGGER
        );
        this.targetingService.setFlagsHudStateProvider(this.ctfFlagStateService::snapshotHudState);
        this.ctfPointsRepository = new CtfPointsRepository(this.getDataDirectory(), LOGGER);
        this.ctfShopConfigRepository = new CtfShopConfigRepository(this.getDataDirectory(), LOGGER);
        this.ctfShopUiService = new CtfShopUiService(this.ctfPointsRepository, this.ctfShopConfigRepository, this::rrCreateItemStackById);
        this.npcArenaRepository = new NpcArenaRepository(this.getDataDirectory(), LOGGER);
        NpcSpawnAdapter npcCommandBridgeAdapter = new NpcSpawnAdapterCommandBridge(LOGGER);
        NpcSpawnAdapter npcFallbackAdapter = new NpcSpawnAdapterFallback(LOGGER);
        this.npcTestService = new NpcTestService(
                this.npcArenaRepository,
                npcCommandBridgeAdapter,
                npcFallbackAdapter,
                LOGGER
        );
        this.ctfMatchEndService = new CtfMatchEndService(
                this.ctfMatchService,
                this.simpleClaimsCtfBridge,
                this.ctfFlagStateService,
                this.ctfPointsRepository,
                this.standSwapService,
                this.targetingService,
                this.playerByUuid,
                LOGGER
        );
        this.targetingService.setMatchTimerEndedCallback(() -> {
            CtfMatchService ms = ctfMatchService;
            if (ms == null) return;
            CtfMatchEndService mes = ctfMatchEndService;
            if (mes == null) return;
            CtfMatchEndService.EndReason reason = ms.consumeStopRequested()
                    ? CtfMatchEndService.EndReason.STOPPED
                    : CtfMatchEndService.EndReason.TIME_EXPIRED;
            mes.endMatch(reason);
        });
        this.targetingService.setPerSliceCallback(() -> {
            NpcTestService nts = npcTestService;
            if (nts != null) {
                nts.processRespawns();
            }

            CtfMatchService ms = ctfMatchService;
            CtfFlagStateService fs = ctfFlagStateService;
            if (ms == null || fs == null) return;
            if (!ms.isRunning()) return;
            fs.processDroppedFlagTimeouts(standSwapService);
        });


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
        this.getCommandRegistry().registerCommand(new RealmRulerCommand(
                this.ctfMatchService,
                this.simpleClaimsCtfBridge,
                this.targetingService,
                this.ctfFlagStateService,
                this.ctfStandRegistryRepository,
                this.ctfPointsRepository,
                this.ctfShopUiService,
                this.npcArenaRepository,
                this.npcTestService
        ));

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

        this.getEventRegistry().register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        LOGGER.atInfo().log("Registered PlayerDisconnectEvent listener (HUD warning reset).");
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, this::onLivingEntityInventoryChange);
        LOGGER.atInfo().log("Registered LivingEntityInventoryChangeEvent listener (CTF flag pickup rules).");


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
            this.getEntityStoreRegistry().registerSystem(targetingService.createLookTargetTrackerSystem());
            LOGGER.atInfo().log("Registered LookTargetTrackerSystem (raycast per tick).");
        } else {
            LOGGER.atInfo().log("LookTargetTrackerSystem is disabled (ENABLE_LOOK_TRACKER=false).");
        }

        // Auto-respawn + teleport back to team spawn during CTF matches.
        this.getEntityStoreRegistry().registerSystem(new CtfAutoRespawnAndTeleportSystem(
                this.ctfMatchService,
                this.ctfFlagStateService,
                this.simpleClaimsCtfBridge,
                this.targetingService,
                LOGGER
        ));
        LOGGER.atInfo().log("Registered CtfAutoRespawnAndTeleportSystem.");
        this.getEntityStoreRegistry().registerSystem(new CtfCarrierSlotLockSystem(this.ctfMatchService, this.ctfFlagStateService));
        this.getEntityStoreRegistry().registerSystem(new CtfCarrierDropBlockSystem(this.ctfMatchService, this.ctfFlagStateService));
        LOGGER.atInfo().log("Registered CTF carrier slot/drop enforcement systems.");
        this.getEntityStoreRegistry().registerSystem(new NpcLifecycleSystem(this.npcTestService));
        LOGGER.atInfo().log("Registered NpcLifecycleSystem.");

        // Shop UI open service (PageManager).
        if (ctfShopUiService != null) {
            this.getEntityStoreRegistry().registerSystem(ctfShopUiService.createSystem());
            LOGGER.atInfo().log("Registered CtfShopUiService system.");
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
        if (rrVerbose() && (USEBLOCK_DEBUG_LIMIT-- > 0 || isStand)) {
            LOGGER.atInfo().log("[RR-USEBLOCK] type=%s clickedId=%s heldId=%s isStand=%s", type, clickedId, heldId, isStand);
        }

        if (isStand) {
            // Optional: in-game confirmation (lets you test without staring at server logs).
            if (rrDebug()) {
                sendPlayerMessage(ctx, MSG_DEBUG_HIT);
            }

            // IMPORTANT:
            // We record the stand location from this event as a fallback.
            // Later, if PlayerInteractLib can't give us a position, we may use this remembered location.
            rememberStandLocationFromUseBlock(event, ctx);

            // Suppress default chest UI for flag stands.
            rrTryCancelEvent(event);

        }
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null) return;
        PlayerRef playerRef = event.getPlayerRef();
        if (playerRef == null || playerRef.getUuid() == null) return;

        String uuid = playerRef.getUuid().toString();
        if (uuid.isBlank()) return;

        if (multipleHudBridge != null) {
            try {
                multipleHudBridge.clearWarnedPlayer(uuid);
            } catch (Throwable ignored) {
            }
        }

        handleCarrierDisconnect(playerRef, uuid);
    }

    private void onLivingEntityInventoryChange(LivingEntityInventoryChangeEvent event) {
        if (event == null) return;
        if (ctfMatchService == null || ctfFlagStateService == null) return;
        if (!ctfMatchService.isRunning()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getUuid() == null) return;

        String uuid = player.getUuid().toString();

        Inventory inv = player.getInventory();
        if (inv == null) return;

        enforceCarrierSlotIntegrity(player, uuid, inv);

        for (CtfMatchService.Team flagTeam : CtfMatchService.Team.values()) {
            if (!ctfFlagStateService.isFlagDropped(flagTeam)) continue;
            String flagItemId = CtfFlagStateService.flagItemIdForTeam(flagTeam);
            if (flagItemId == null) continue;

            InventorySlot slot = findFirstFlagSlot(inv, flagItemId);
            if (slot == null) continue;

            applyDroppedFlagPickupRules(player, uuid, flagTeam, flagItemId, slot);
        }
    }

    private void handleCarrierDisconnect(PlayerRef playerRef, String uuid) {
        if (playerRef == null || uuid == null || uuid.isBlank()) return;
        if (ctfMatchService == null || ctfFlagStateService == null) return;
        if (!ctfMatchService.isRunning()) return;
        if (!ctfMatchService.isActiveMatchParticipant(uuid)) return;

        CtfMatchService.Team carriedFlag = ctfFlagStateService.carriedFlagFor(uuid);
        if (carriedFlag == null) return;

        Player player = null;
        try {
            player = playerRef.getComponent(Player.getComponentType());
        } catch (Throwable ignored) {
            player = null;
        }

        ItemStack dropStack = null;
        if (player != null) {
            dropStack = ctfFlagStateService.removeOneFlagFromPlayer(player, carriedFlag);
        }
        if (dropStack == null) {
            String flagItemId = CtfFlagStateService.flagItemIdForTeam(carriedFlag);
            if (flagItemId != null) {
                dropStack = rrCreateItemStackById(flagItemId, 1);
            }
        }

        String worldName = null;
        double x = 0;
        double y = 0;
        double z = 0;
        boolean havePosition = false;

        try {
            Transform transform = playerRef.getTransform();
            if (transform != null && transform.getPosition() != null) {
                x = transform.getPosition().getX();
                y = transform.getPosition().getY();
                z = transform.getPosition().getZ();
                havePosition = true;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (playerRef.getWorldUuid() != null) {
                World world = Universe.get().getWorld(playerRef.getWorldUuid());
                if (world != null) {
                    worldName = world.getName();
                }
            }
        } catch (Throwable ignored) {
        }

        if (worldName == null || worldName.isBlank() || !havePosition) {
            TargetingService.PlayerLocationSnapshot snapshot = (targetingService == null) ? null : targetingService.getLatestPlayerLocation(uuid);
            if (snapshot != null && snapshot.isValid()) {
                worldName = snapshot.worldName();
                x = snapshot.x();
                y = snapshot.y();
                z = snapshot.z();
                havePosition = true;
            }
        }

        if (dropStack != null) {
            boolean dropped = false;

            if (player != null) {
                dropped = dropItemNearPlayer(player, dropStack);
            }

            if (!dropped) {
                try {
                    if (playerRef.getReference() != null && playerRef.getReference().isValid() && playerRef.getWorldUuid() != null) {
                        World world = Universe.get().getWorld(playerRef.getWorldUuid());
                        if (world != null) {
                            ItemUtils.dropItem(playerRef.getReference(), dropStack, world.getEntityStore().getStore());
                            dropped = true;
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to drop carrier flag on disconnect. uuid=%s", uuid);
                }
            }

            if (!dropped && havePosition && worldName != null && !worldName.isBlank()) {
                World world = Universe.get().getWorld(worldName);
                if (world != null) {
                    try {
                        ItemComponent.generateItemDrop(world.getEntityStore().getStore(), dropStack, new com.hypixel.hytale.math.vector.Vector3d(x, y, z), new com.hypixel.hytale.math.vector.Vector3f(0f, 0f, 0f), 0f, 0f, 0f);
                    } catch (Throwable t) {
                        LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed fallback drop generation on disconnect. uuid=%s", uuid);
                    }
                }
            }
        }

        if (havePosition && worldName != null && !worldName.isBlank()) {
            ctfFlagStateService.markCarrierDropped(uuid, worldName, x, y, z);
        }
    }

    private void enforceCarrierSlotIntegrity(Player player, String uuid, Inventory inv) {
        if (player == null || uuid == null || uuid.isBlank() || inv == null) return;
        if (ctfFlagStateService == null) return;

        CtfMatchService.Team carriedFlag = ctfFlagStateService.carriedFlagFor(uuid);
        Byte lockedSlot = ctfFlagStateService.lockedHotbarSlotForCarrier(uuid);
        if (carriedFlag == null || lockedSlot == null) return;

        String flagItemId = CtfFlagStateService.flagItemIdForTeam(carriedFlag);
        if (flagItemId == null) return;

        ItemContainer hotbar = inv.getHotbar();
        if (hotbar == null) return;

        short slot = (short) (lockedSlot & 0xFF);
        ItemStack inLockedSlot = hotbar.getItemStack(slot);
        if (inLockedSlot != null && flagItemId.equals(inLockedSlot.getItemId())) {
            inv.setActiveHotbarSlot((byte) (lockedSlot & 0xFF));
            return;
        }

        InventorySlot found = findFirstFlagSlot(inv, flagItemId);
        if (found == null) return;

        found.container().removeItemStackFromSlot(found.slot(), 1);
        ItemStack movedFlag = rrCreateItemStackById(flagItemId, 1);
        if (movedFlag == null) return;

        hotbar.setItemStackForSlot(slot, movedFlag);
        inv.setActiveHotbarSlot((byte) (lockedSlot & 0xFF));
        player.sendInventory();
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
     *  3) Subscribe a Flow.Subscriber so we receive interaction event callbacks
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

            if (!(publisherObj instanceof Flow.Publisher<?>)) {
                LOGGER.atWarning().log("[RR-PI] Unexpected publisher type (not Flow.Publisher): %s", publisherObj.getClass().getName());
                return;
            }

            Flow.Publisher<?> publisher = (Flow.Publisher<?>) publisherObj;

            // Flow.Subscriber is the Java reactive-streams style subscription interface.
            // Once subscribed, PlayerInteractLib will call onNext(...) for each interaction event.
            Flow.Subscriber<Object> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    // Request all events. This is the normal pattern for "we want the full stream".
                    subscription.request(Long.MAX_VALUE);
                    LOGGER.atInfo().log("[RR-PI] Subscribed OK. publisher=%s", publisherObj.getClass().getName());
                }

                @Override
                public void onNext(Object event) {
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

            @SuppressWarnings("rawtypes")
            Flow.Publisher rawPublisher = (Flow.Publisher) publisher;
            rawPublisher.subscribe(subscriber);

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
     * Phase 1: ignore item-in-hand and simply toggle Empty <-> Blue on any stand interaction.
     * Phase 2+: set this false and use itemInHand to choose stand color based on flags.
     */
    private static final boolean PHASE1_TOGGLE_BLUE_ONLY = false;

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
    private void onPlayerInteraction(Object event) {
        if (modeManager != null) {
            modeManager.dispatchPlayerAction(event);
            return;
        }
        handleCtfAction(event); // fallback
    }

    // -----------------------------------------------------------------------------
// MODE BRIDGE: temporary accessors so CtfMode can migrate logic safely.
// This avoids changing functionality while we relocate code out of Realm_Ruler.
// -----------------------------------------------------------------------------

    public com.Chris__.realm_ruler.platform.PlayerInteractAdapter rrPi() {
        return pi;
    }

    /**
     * Best-effort event cancellation (reflection-safe).
     * Used to suppress the chest UI for flag stands and to block invalid swaps.
     */
    public void rrTryCancelEvent(Object event) {
        if (event == null) return;

        for (String mName : new String[]{"setCancelled", "setCanceled", "cancel"}) {
            // setX(boolean)
            try {
                Method m = event.getClass().getMethod(mName, boolean.class);
                m.invoke(event, true);
                return;
            } catch (Throwable ignored) {}

            // cancel() with no args
            try {
                Method m = event.getClass().getMethod(mName);
                m.invoke(event);
                return;
            } catch (Throwable ignored) {}
        }
    }

    public TargetingService TargetingService() {
        return targetingService;
    }

    public HytaleLogger rrLogger() {
        return LOGGER;
    }

    public boolean rrDebug() {
        return RrDebugFlags.debug();
    }

    public boolean rrVerbose() {
        return RrDebugFlags.verbose();
    }

    public boolean rrPhase1ToggleBlueOnly() {
        return PHASE1_TOGGLE_BLUE_ONLY;
    }

    public String rrTryGetBlockIdAt(World world, int x, int y, int z) {
        return tryGetBlockIdAt(world, x, y, z);
    }

    public void rrSwapStandAt(TargetingModels.BlockLocation loc, String standKey) {
        if (loc == null || loc.world == null || standKey == null) return;

        // Keep world writes centralized through your WORLD seam
        // (StandSwapService handles asset-id validation + chunk loaded checks)
        standSwapService.swapStand(loc.world, loc.x, loc.y, loc.z, standKey);
    }

    public void rrRunOnTick(Runnable r) {
        runOnTick(r);
    }

    public Player rrResolvePlayer(String uuid) {
        return playerByUuid.get(uuid);
    }

    public boolean rrIsActiveCtfParticipant(String uuid) {
        if (ctfMatchService == null || uuid == null || uuid.isBlank()) return false;
        return ctfMatchService.isActiveMatchParticipant(uuid);
    }

    public CtfMatchService.Team rrActiveMatchTeamFor(String uuid) {
        if (ctfMatchService == null || uuid == null || uuid.isBlank()) return null;
        return ctfMatchService.activeMatchTeamFor(uuid);
    }

    public String rrCtfChunkOwnerTeam(World world, int blockX, int blockZ) {
        if (world == null) return null;
        if (simpleClaimsCtfBridge == null || !simpleClaimsCtfBridge.isAvailable()) return null;

        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) return null;

        int chunkX = ChunkUtil.chunkCoordinate(blockX);
        int chunkZ = ChunkUtil.chunkCoordinate(blockZ);
        String ownerTeam = simpleClaimsCtfBridge.getTeamForChunk(worldName, chunkX, chunkZ);
        if (ownerTeam == null) return null;

        String normalized = ownerTeam.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public boolean rrCtfIsCarryingAnyFlag(String uuid) {
        if (ctfFlagStateService == null || uuid == null || uuid.isBlank()) return false;
        return ctfFlagStateService.isCarryingAnyFlag(uuid);
    }

    public void rrCtfOnFlagDeposited(String uuid, String flagItemId, TargetingModels.BlockLocation loc) {
        if (ctfFlagStateService == null) return;
        if (uuid == null || uuid.isBlank()) return;
        if (flagItemId == null || flagItemId.isBlank()) return;
        if (loc == null || loc.world == null) return;
        String worldName = loc.world.getName();
        ctfFlagStateService.onDeposited(uuid, flagItemId, worldName, loc.x, loc.y, loc.z);
    }

    public void rrCtfOnFlagWithdrawn(String uuid,
                                     String holderName,
                                     String flagItemId,
                                     TargetingModels.BlockLocation loc,
                                     byte lockedHotbarSlot) {
        if (ctfFlagStateService == null) return;
        if (uuid == null || uuid.isBlank()) return;
        if (flagItemId == null || flagItemId.isBlank()) return;
        if (loc == null || loc.world == null) return;
        String worldName = loc.world.getName();
        ctfFlagStateService.onWithdrawn(uuid, holderName, flagItemId, worldName, loc.x, loc.y, loc.z, lockedHotbarSlot);
    }

    public void handleCtfAction(Object action) {
        // Legacy bridge: allow direct calls (or fallback paths) to reuse the migrated mode logic.
        if (ctfMode != null) {
            ctfMode.onPlayerAction(action);
        }

        // If we ever reach here, modes haven't been initialized yet.
        // Intentionally no-op to avoid changing behavior in unexpected init states.
    }

    // TARGETING: one place that turns (uuid + event + chain) into a BlockLocation
    public TargetingModels.TargetingResult resolveTarget(String uuid, Object event, Object chain) {
        return (targetingService == null) ? null : targetingService.resolveTarget(uuid, event, chain);
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

    public String tryGetBlockIdAt(World world, int x, int y, int z) {
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

// ---------- Helpers ----------

    private static void dumpPlayerMethods() {
        try {
            Class<?> c = com.hypixel.hytale.server.core.entity.entities.Player.class;

            LOGGER.atInfo().log("[RR-DUMP] Player public methods containing: hand|held|inventory|item|container|slot|give|set");

            for (java.lang.reflect.Method m : c.getMethods()) { // public methods incl inherited
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);

                if (name.contains("hand")
                        || name.contains("held")
                        || name.contains("inventory")
                        || name.contains("item")
                        || name.contains("container")
                        || name.contains("slot")
                        || name.contains("give")
                        || name.contains("set")) {

                    LOGGER.atInfo().log("[RR-DUMP] %s", m.toString());
                }
            }
        } catch (Throwable t) {
            // Your logger supports withCause(t); use atWarning as "error-level"
            LOGGER.atWarning().withCause(t).log("[RR-DUMP] Failed to dump Player methods");
        }
    }
    private static void dumpMethods(Class<?> c, String label) {
        try {
            LOGGER.atInfo().log("[RR-DUMP] ---- %s (%s) ----", label, c.getName());
            for (java.lang.reflect.Method m : c.getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("hand")
                        || name.contains("held")
                        || name.contains("inventory")
                        || name.contains("item")
                        || name.contains("container")
                        || name.contains("slot")
                        || name.contains("hotbar")
                        || name.contains("equip")
                        || name.contains("add")
                        || name.contains("remove")
                        || name.contains("set")
                        || name.contains("get")) {
                    LOGGER.atInfo().log("[RR-DUMP] %s", m.toString());
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-DUMP] Failed dumping %s", label);
        }
    }

    private static void dumpInventoryApis() {
        dumpMethods(com.hypixel.hytale.server.core.inventory.Inventory.class, "Inventory");
        dumpMethods(com.hypixel.hytale.server.core.inventory.ItemStack.class, "ItemStack");
        dumpMethods(com.hypixel.hytale.server.core.inventory.container.ItemContainer.class, "ItemContainer");
    }

    public ItemStack rrCreateItemStackById(String itemId, int amount) {
        return createItemStackById(itemId, amount);
    }

    private ItemStack createItemStackById(String itemId, int amount) {
        if (itemId == null) return null;

        // Try common constructors first
        try {
            // (String, int)
            try {
                return ItemStack.class.getConstructor(String.class, int.class).newInstance(itemId, amount);
            } catch (Throwable ignored) {}

            // (String)
            try {
                ItemStack s = ItemStack.class.getConstructor(String.class).newInstance(itemId);
                // try to set amount if method exists
                try {
                    var m = ItemStack.class.getMethod("setAmount", int.class);
                    m.invoke(s, amount);
                } catch (Throwable ignored2) {}
                return s;
            } catch (Throwable ignored) {}

            // Try static factories: of/create/from (String,int)
            for (String name : new String[]{"of", "create", "from"}) {
                try {
                    var m = ItemStack.class.getMethod(name, String.class, int.class);
                    Object v = m.invoke(null, itemId, amount);
                    if (v instanceof ItemStack is) return is;
                } catch (Throwable ignored) {}
            }

            // Try numeric ID via Item asset map (reflection; avoids compile dependency)
            Integer idx = tryGetItemAssetIndex(itemId);
            if (idx != null) {
                // (int, int)
                try {
                    return ItemStack.class.getConstructor(int.class, int.class).newInstance(idx, amount);
                } catch (Throwable ignored) {}

                // static factories: of/create/from (int,int)
                for (String name : new String[]{"of", "create", "from"}) {
                    try {
                        var m = ItemStack.class.getMethod(name, int.class, int.class);
                        Object v = m.invoke(null, idx, amount);
                        if (v instanceof ItemStack is) return is;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private Integer tryGetItemAssetIndex(String itemId) {
        try {
            Class<?> itemCls = Class.forName("com.hypixel.hytale.server.core.asset.type.item.config.Item");
            Object assetMap = itemCls.getMethod("getAssetMap").invoke(null);
            Object idx = assetMap.getClass().getMethod("getIndex", String.class).invoke(assetMap, itemId);
            if (idx instanceof Integer i && i != Integer.MIN_VALUE) return i;
        } catch (Throwable ignored) {}
        return null;
    }
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

            if (targetingService != null) {
                targetingService.rememberPendingStandLocation(new BlockLocation(world, x, y, z));
            }
        } catch (Throwable ignored) {}
    }

    private record InventorySlot(ItemContainer container, short slot, boolean hotbar) {
    }

    private void applyDroppedFlagPickupRules(Player player,
                                             String uuid,
                                             CtfMatchService.Team flagTeam,
                                             String flagItemId,
                                             InventorySlot pickupSlot) {
        if (player == null || uuid == null || uuid.isBlank()) return;
        if (flagTeam == null || flagItemId == null || flagItemId.isBlank()) return;
        if (pickupSlot == null || pickupSlot.container() == null) return;
        if (ctfFlagStateService == null || ctfMatchService == null) return;

        CtfMatchService.Team playerTeam = ctfMatchService.activeMatchTeamFor(uuid);

        // Non-participants cannot take dropped CTF flags.
        if (playerTeam == null) {
            removeOneFlagFromSlotAndDrop(player, pickupSlot, flagItemId);
            return;
        }

        // Same-team pickup should immediately return the flag to its home stand.
        if (playerTeam == flagTeam) {
            removeOneFlagFromSlot(player, pickupSlot);
            boolean returned = ctfFlagStateService.tryReturnDroppedFlagToHome(flagTeam, standSwapService);
            if (!returned) {
                dropItemNearPlayer(player, rrCreateItemStackById(flagItemId, 1));
            }
            return;
        }

        // One-flag-per-player invariant.
        if (ctfFlagStateService.isCarryingAnyFlag(uuid)) {
            removeOneFlagFromSlotAndDrop(player, pickupSlot, flagItemId);
            return;
        }

        Inventory inv = player.getInventory();
        if (inv == null) return;
        ItemContainer hotbar = inv.getHotbar();
        if (hotbar == null) return;

        byte targetHotbarSlot;
        if (pickupSlot.hotbar()) {
            targetHotbarSlot = (byte) (pickupSlot.slot() & 0xFF);
        } else {
            Byte emptyHotbarSlot = findFirstEmptyHotbarSlot(inv);
            if (emptyHotbarSlot == null) {
                removeOneFlagFromSlotAndDrop(player, pickupSlot, flagItemId);
                return;
            }
            targetHotbarSlot = emptyHotbarSlot;

            pickupSlot.container().removeItemStackFromSlot(pickupSlot.slot(), 1);
            ItemStack movedFlag = rrCreateItemStackById(flagItemId, 1);
            if (movedFlag == null) {
                dropItemNearPlayer(player, rrCreateItemStackById(flagItemId, 1));
                return;
            }
            hotbar.setItemStackForSlot((short) (targetHotbarSlot & 0xFF), movedFlag);
        }

        inv.setActiveHotbarSlot(targetHotbarSlot);
        player.sendInventory();
        ctfFlagStateService.assignFlagCarrier(flagTeam, uuid, player.getDisplayName(), targetHotbarSlot);
    }

    private void removeOneFlagFromSlotAndDrop(Player player, InventorySlot slot, String flagItemId) {
        if (player == null || slot == null || slot.container() == null) return;
        removeOneFlagFromSlot(player, slot);
        dropItemNearPlayer(player, rrCreateItemStackById(flagItemId, 1));
    }

    private void removeOneFlagFromSlot(Player player, InventorySlot slot) {
        if (player == null || slot == null || slot.container() == null) return;
        slot.container().removeItemStackFromSlot(slot.slot(), 1);
        player.sendInventory();
    }

    private InventorySlot findFirstFlagSlot(Inventory inv, String flagItemId) {
        if (inv == null || flagItemId == null || flagItemId.isBlank()) return null;

        InventorySlot inHotbar = findFirstFlagSlotInContainer(inv.getHotbar(), flagItemId, true);
        if (inHotbar != null) return inHotbar;

        InventorySlot inStorage = findFirstFlagSlotInContainer(inv.getStorage(), flagItemId, false);
        if (inStorage != null) return inStorage;

        InventorySlot inBackpack = findFirstFlagSlotInContainer(inv.getBackpack(), flagItemId, false);
        if (inBackpack != null) return inBackpack;

        InventorySlot inTools = findFirstFlagSlotInContainer(inv.getTools(), flagItemId, false);
        if (inTools != null) return inTools;

        InventorySlot inUtility = findFirstFlagSlotInContainer(inv.getUtility(), flagItemId, false);
        if (inUtility != null) return inUtility;

        return findFirstFlagSlotInContainer(inv.getArmor(), flagItemId, false);
    }

    private InventorySlot findFirstFlagSlotInContainer(ItemContainer container, String flagItemId, boolean hotbar) {
        if (container == null || flagItemId == null || flagItemId.isBlank()) return null;

        final short[] found = new short[]{-1};
        container.forEach((slot, stack) -> {
            if (found[0] >= 0) return;
            if (stack == null) return;
            if (!flagItemId.equals(stack.getItemId())) return;
            found[0] = slot;
        });

        if (found[0] < 0) return null;
        return new InventorySlot(container, found[0], hotbar);
    }

    private static Byte findFirstEmptyHotbarSlot(Inventory inv) {
        if (inv == null) return null;
        ItemContainer hotbar = inv.getHotbar();
        if (hotbar == null) return null;

        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.getQuantity() <= 0) {
                return (byte) (slot & 0xFF);
            }
        }
        return null;
    }

    private boolean dropItemNearPlayer(Player player, ItemStack stack) {
        if (player == null || stack == null) return false;
        if (player.getReference() == null || !player.getReference().isValid()) return false;
        if (player.getWorld() == null) return false;

        try {
            ItemUtils.dropItem(player.getReference(), stack, player.getWorld().getEntityStore().getStore());
            return true;
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to drop item near player. uuid=%s", player.getUuid());
            return false;
        }
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

    public void runOnTick(Runnable r) {
        if (r != null) tickQueue.add(r);
    }
}
