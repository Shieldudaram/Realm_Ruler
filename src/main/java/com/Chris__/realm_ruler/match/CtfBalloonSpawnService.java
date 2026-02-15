package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

public final class CtfBalloonSpawnService {

    public static final String BALLOON_ITEM_ID = "Loot_Balloon_Common";
    public static final String BALLOON_ROLE_ID = "Loot_Balloon_Entity_Common";

    private static final long SPAWN_INTERVAL_NANOS = 30_000_000_000L;
    private static final long DESPAWN_AFTER_NANOS = 45_000_000_000L;
    private static final long PROCESS_SLICE_INTERVAL_NANOS = 50_000_000L;
    private static final long BACKEND_WARN_INTERVAL_NANOS = 10_000_000_000L;
    private static final long FALLBACK_FAILURE_COOLDOWN_NANOS = 12_000_000_000L;
    private static final int MAX_ACTIVE_BALLOONS = 6;
    private static final int MAX_ATTEMPTS_PER_SPAWN = 10;
    private static final List<String> FALLBACK_COMMAND_SUFFIXES = List.of("", " 1", " 1 1");

    private static final String REWARD_PLACE_1_ITEM_ID = BALLOON_ITEM_ID;
    private static final String REWARD_PLACE_2_ITEM_ID = "Rare_Loot_Bag";
    private static final String REWARD_PLACE_3_PLUS_ITEM_ID = "Epic_Loot_Bag";

    private static final Query<EntityStore> NPC_QUERY = Query.and(
            NPCEntity.getComponentType(),
            TransformComponent.getComponentType()
    );

    public record StatusSnapshot(boolean matchRunning,
                                 boolean regionConfigured,
                                 boolean regionReady,
                                 String regionWorldName,
                                 boolean regionWorldResolved,
                                 int activeCount,
                                 int maxActive,
                                 long secondsUntilNextSpawn,
                                 boolean directApiReady,
                                 boolean fallbackReady,
                                 boolean balloonRoleResolvable,
                                 String selectedFallbackTemplate,
                                 long fallbackCooldownRemainingSeconds,
                                 String lastFallbackError,
                                 String lastFallbackAttemptTemplate,
                                 String blockingReason) {
    }

    public record SpawnNowResult(int requestedCount, int spawnedCount, int activeCountAfter, String message) {
    }

    private record NpcSnapshot(Ref<EntityStore> ref, Vector3d position) {
    }

    private final Object lock = new Object();
    private final CtfMatchService matchService;
    private final CtfRegionRepository regionRepository;
    private final CtfFlagStateService flagStateService;
    private final BiFunction<String, Integer, ItemStack> itemStackFactory;
    private final HytaleLogger logger;

    private final Map<Ref<EntityStore>, Long> activeBalloonsByRef = new LinkedHashMap<>();
    private long nextSpawnAtNanos = 0L;
    private long nextSliceAtNanos = 0L;
    private long nextBackendWarnAtNanos = 0L;
    private long fallbackCommandCooldownUntilNanos = 0L;
    private int forcedSpawnQueue = 0;
    private String fallbackRequesterHint = null;
    private String cachedFallbackCommandSuffix = null;
    private String lastFallbackErrorSummary = null;
    private String lastFallbackAttemptTemplate = null;

    public CtfBalloonSpawnService(CtfMatchService matchService,
                                  CtfRegionRepository regionRepository,
                                  CtfFlagStateService flagStateService,
                                  BiFunction<String, Integer, ItemStack> itemStackFactory,
                                  HytaleLogger logger) {
        this.matchService = matchService;
        this.regionRepository = regionRepository;
        this.flagStateService = flagStateService;
        this.itemStackFactory = itemStackFactory;
        this.logger = logger;
    }

    public void processSlice(Store<EntityStore> store,
                             CommandBuffer<EntityStore> commandBuffer,
                             @Nullable String fallbackRequesterUuid) {
        if (store == null || commandBuffer == null) return;

        long now = System.nanoTime();
        if (!acquireSlice(now)) return;
        cleanupExpired(commandBuffer, now);

        if (matchService == null || !matchService.isRunning()) {
            cleanupAll(commandBuffer);
            return;
        }
        if (regionRepository == null) return;

        CtfRegionRepository.RegionDefinition region = regionRepository.get();
        if (region == null || !region.enabled() || !region.hasBounds()) {
            return;
        }
        World regionWorld = Universe.get().getWorld(region.worldName());
        if (regionWorld == null) {
            return;
        }
        if (!isStoreForWorld(store, regionWorld)) {
            return;
        }

        int forcedToSpawnNow = 0;
        String forcedRequesterHint = null;
        synchronized (lock) {
            if (fallbackRequesterUuid != null && !fallbackRequesterUuid.isBlank()) {
                fallbackRequesterHint = fallbackRequesterUuid;
            }
            if (forcedSpawnQueue > 0) {
                int available = Math.max(0, MAX_ACTIVE_BALLOONS - activeBalloonsByRef.size());
                forcedToSpawnNow = Math.min(available, forcedSpawnQueue);
                forcedSpawnQueue -= forcedToSpawnNow;
                forcedRequesterHint = fallbackRequesterHint;
            }
        }

        for (int index = 0; index < forcedToSpawnNow; index++) {
            if (!spawnOne(store, commandBuffer, regionWorld, region, now, forcedRequesterHint)) break;
        }

        synchronized (lock) {
            if (activeBalloonsByRef.size() >= MAX_ACTIVE_BALLOONS) return;
            if (nextSpawnAtNanos > 0L && now < nextSpawnAtNanos) return;
            nextSpawnAtNanos = now + SPAWN_INTERVAL_NANOS;
        }

        spawnOne(store, commandBuffer, regionWorld, region, now, fallbackRequesterUuid);
    }

    public StatusSnapshot statusSnapshot() {
        long now = System.nanoTime();

        CtfRegionRepository.RegionDefinition region = (regionRepository == null) ? null : regionRepository.get();
        boolean regionConfigured = region != null;
        boolean regionReady = region != null && region.enabled() && region.hasBounds();
        World regionWorld = (region == null || region.worldName() == null) ? null : Universe.get().getWorld(region.worldName());
        boolean regionWorldResolved = regionWorld != null;
        boolean directApiReady = isDirectApiReady();
        boolean fallbackReady = hasFallbackRequester();
        boolean roleResolvable = isBalloonRoleResolvable();

        int activeCount;
        long secondsUntilNextSpawn;
        long fallbackCooldownRemainingSeconds;
        String selectedFallbackTemplate;
        String lastFallbackError;
        String lastFallbackAttemptTemplate;
        synchronized (lock) {
            pruneInvalidRefsLocked();
            activeCount = activeBalloonsByRef.size();
            if (nextSpawnAtNanos <= 0L) {
                secondsUntilNextSpawn = -1L;
            } else {
                long nanosLeft = Math.max(0L, nextSpawnAtNanos - now);
                secondsUntilNextSpawn = (long) Math.ceil(nanosLeft / 1_000_000_000.0d);
            }
            fallbackCooldownRemainingSeconds = (fallbackCommandCooldownUntilNanos <= now)
                    ? 0L
                    : (long) Math.ceil((fallbackCommandCooldownUntilNanos - now) / 1_000_000_000.0d);
            selectedFallbackTemplate = templateLabelForSuffix(cachedFallbackCommandSuffix);
            lastFallbackError = lastFallbackErrorSummary;
            lastFallbackAttemptTemplate = this.lastFallbackAttemptTemplate;
        }

        String blocking = resolveBlockingReason(
                region,
                regionWorld,
                directApiReady,
                fallbackReady,
                roleResolvable,
                activeCount,
                fallbackCooldownRemainingSeconds
        );

        return new StatusSnapshot(
                matchService != null && matchService.isRunning(),
                regionConfigured,
                regionReady,
                (region == null) ? null : region.worldName(),
                regionWorldResolved,
                activeCount,
                MAX_ACTIVE_BALLOONS,
                secondsUntilNextSpawn,
                directApiReady,
                fallbackReady,
                roleResolvable,
                selectedFallbackTemplate,
                fallbackCooldownRemainingSeconds,
                lastFallbackError,
                lastFallbackAttemptTemplate,
                blocking
        );
    }

    public SpawnNowResult spawnNow(int requestedCount) {
        return spawnNow(requestedCount, null);
    }

    public SpawnNowResult spawnNow(int requestedCount, @Nullable String requesterUuid) {
        int target = Math.max(1, requestedCount);
        StatusSnapshot status = statusSnapshot();
        if (status.blockingReason() != null) {
            return new SpawnNowResult(target, 0, status.activeCount(), status.blockingReason());
        }

        int queued;
        int activeCount;
        synchronized (lock) {
            int available = Math.max(0, MAX_ACTIVE_BALLOONS - activeBalloonsByRef.size());
            queued = Math.min(target, available);
            if (queued > 0) {
                forcedSpawnQueue += queued;
                if (requesterUuid != null && !requesterUuid.isBlank()) {
                    fallbackRequesterHint = requesterUuid;
                }
            }
            activeCount = activeBalloonsByRef.size();
        }
        String message = (queued <= 0)
                ? "Active balloon cap reached (" + MAX_ACTIVE_BALLOONS + ")."
                : "Queued " + queued + " balloon spawn(s).";
        return new SpawnNowResult(target, queued, activeCount, message);
    }

    public void cleanupAll(CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer == null) return;
        synchronized (lock) {
            if (activeBalloonsByRef.isEmpty()) {
                nextSpawnAtNanos = 0L;
                forcedSpawnQueue = 0;
                fallbackRequesterHint = null;
                fallbackCommandCooldownUntilNanos = 0L;
                cachedFallbackCommandSuffix = null;
                lastFallbackErrorSummary = null;
                lastFallbackAttemptTemplate = null;
                return;
            }

            for (Ref<EntityStore> ref : activeBalloonsByRef.keySet()) {
                safeRemove(commandBuffer, ref);
            }
            activeBalloonsByRef.clear();
            nextSpawnAtNanos = 0L;
            forcedSpawnQueue = 0;
            fallbackRequesterHint = null;
            fallbackCommandCooldownUntilNanos = 0L;
            cachedFallbackCommandSuffix = null;
            lastFallbackErrorSummary = null;
            lastFallbackAttemptTemplate = null;
        }
    }

    public boolean consumeTrackedBalloon(Ref<EntityStore> ref) {
        if (ref == null) return false;
        synchronized (lock) {
            return activeBalloonsByRef.remove(ref) != null;
        }
    }

    public boolean isTrackedBalloon(Ref<EntityStore> ref) {
        if (ref == null) return false;
        synchronized (lock) {
            return activeBalloonsByRef.containsKey(ref);
        }
    }

    public void onBalloonPopped(@Nullable String attackerUuid, @Nullable Player attackerPlayer) {
        RewardDecision decision = resolveRewardDecision(attackerUuid);
        if (decision == null || attackerPlayer == null) return;

        ItemStack rewardStack;
        try {
            rewardStack = (itemStackFactory == null) ? null : itemStackFactory.apply(decision.itemId(), 1);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to create balloon reward stack. itemId=%s", decision.itemId());
            return;
        }
        if (rewardStack == null) {
            logger.atWarning().log("[RR-CTF] Balloon reward item unavailable: %s", decision.itemId());
            return;
        }

        Inventory inventory = attackerPlayer.getInventory();
        if (inventory == null) return;
        ItemContainer combined = inventory.getCombinedBackpackStorageHotbar();
        if (combined == null) return;

        List<ItemStack> rewards = List.of(rewardStack);
        if (!combined.canAddItemStacks(rewards)) {
            attackerPlayer.sendMessage(Message.raw("[RealmRuler] Balloon popped, but your inventory is full."));
            return;
        }

        try {
            combined.addItemStacks(rewards);
            attackerPlayer.sendInventory();
            attackerPlayer.sendMessage(Message.raw("[RealmRuler] Balloon reward: " + decision.itemId() + " (team place #" + decision.place() + ")."));
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to grant balloon reward. uuid=%s item=%s", attackerUuid, decision.itemId());
        }
    }

    private @Nullable RewardDecision resolveRewardDecision(@Nullable String attackerUuid) {
        if (attackerUuid == null || attackerUuid.isBlank()) return null;
        if (matchService == null || flagStateService == null) return null;
        if (!matchService.isRunning()) return null;

        CtfMatchService.Team team = matchService.activeMatchTeamFor(attackerUuid);
        if (team == null) return null;

        int place = resolveTeamRankNow(team);
        String rewardItemId;
        if (place <= 1) rewardItemId = REWARD_PLACE_1_ITEM_ID;
        else if (place == 2) rewardItemId = REWARD_PLACE_2_ITEM_ID;
        else rewardItemId = REWARD_PLACE_3_PLUS_ITEM_ID;
        return new RewardDecision(team, place, rewardItemId);
    }

    private int resolveTeamRankNow(CtfMatchService.Team team) {
        if (team == null || flagStateService == null) return 4;

        Map<String, Integer> scores = flagStateService.computeScoresAtEnd();
        EnumMap<CtfMatchService.Team, Integer> scoreByTeam = new EnumMap<>(CtfMatchService.Team.class);
        for (CtfMatchService.Team candidate : CtfMatchService.Team.values()) {
            scoreByTeam.put(candidate, scores.getOrDefault(candidate.displayName(), 0));
        }

        List<Integer> orderedDistinctScores = scoreByTeam.values()
                .stream()
                .distinct()
                .sorted((left, right) -> Integer.compare(right, left))
                .toList();

        EnumMap<CtfMatchService.Team, Integer> placeByTeam = new EnumMap<>(CtfMatchService.Team.class);
        int place = 1;
        for (Integer score : orderedDistinctScores) {
            for (CtfMatchService.Team candidate : CtfMatchService.Team.values()) {
                if (scoreByTeam.getOrDefault(candidate, 0).equals(score)) {
                    placeByTeam.put(candidate, place);
                }
            }
            place++;
        }

        return placeByTeam.getOrDefault(team, 4);
    }

    private void cleanupExpired(CommandBuffer<EntityStore> commandBuffer, long nowNanos) {
        synchronized (lock) {
            Iterator<Map.Entry<Ref<EntityStore>, Long>> iterator = activeBalloonsByRef.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Ref<EntityStore>, Long> entry = iterator.next();
                Ref<EntityStore> ref = entry.getKey();
                Long expiresAt = entry.getValue();

                if (ref == null || !ref.isValid()) {
                    iterator.remove();
                    continue;
                }
                if (expiresAt == null || nowNanos < expiresAt) continue;

                safeRemove(commandBuffer, ref);
                iterator.remove();
            }
        }
    }

    private boolean spawnOne(Store<EntityStore> store,
                             CommandBuffer<EntityStore> commandBuffer,
                             World regionWorld,
                             CtfRegionRepository.RegionDefinition region,
                             long nowNanos,
                             @Nullable String fallbackRequesterUuid) {
        if (region == null) return false;
        if (store == null || commandBuffer == null || regionWorld == null) return false;
        if (!isBalloonRoleResolvable()) {
            warnBackendUnavailable(nowNanos, "role unavailable: " + BALLOON_ROLE_ID);
            return false;
        }
        if (!isDirectApiReady() && !hasFallbackRequester()) {
            warnBackendUnavailable(nowNanos, "no direct API or fallback requester available");
            return false;
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_SPAWN; attempt++) {
            int x = ThreadLocalRandom.current().nextInt(region.minX(), region.maxX() + 1);
            int z = ThreadLocalRandom.current().nextInt(region.minZ(), region.maxZ() + 1);

            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            WorldChunk chunk = regionWorld.getChunkIfLoaded(chunkIndex);
            if (chunk == null) {
                continue;
            }

            int y = (int) chunk.getHeight(x, z) + 1;
            Ref<EntityStore> spawned = spawnBalloonNpc(
                    store,
                    regionWorld,
                    new Vector3d(x + 0.5d, y + 0.25d, z + 0.5d),
                    fallbackRequesterUuid
            );
            if (spawned == null) {
                continue;
            }

            synchronized (lock) {
                activeBalloonsByRef.put(spawned, nowNanos + DESPAWN_AFTER_NANOS);
            }
            return true;
        }

        return false;
    }

    private void warnBackendUnavailable(long nowNanos, String reason) {
        synchronized (lock) {
            if (nowNanos < nextBackendWarnAtNanos) return;
            nextBackendWarnAtNanos = nowNanos + BACKEND_WARN_INTERVAL_NANOS;
        }
        logger.atWarning().log("[RR-CTF] Balloon spawn skipped: %s", reason);
    }

    private @Nullable Ref<EntityStore> spawnBalloonNpc(Store<EntityStore> store,
                                                        World world,
                                                        Vector3d position,
                                                        @Nullable String fallbackRequesterUuid) {
        if (store == null || world == null || position == null) return null;

        Ref<EntityStore> spawned = trySpawnWithDirectApi(store, position);
        if (spawned != null) {
            if (RrDebugFlags.verbose()) {
                logger.atInfo().log("[RR-CTF] Balloon spawned via direct NPC API.");
            }
            return spawned;
        }

        Ref<EntityStore> fallbackSpawned = trySpawnWithCommandFallback(store, world, position, fallbackRequesterUuid);
        if (fallbackSpawned != null && RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] Balloon spawned via command fallback.");
        }
        return fallbackSpawned;
    }

    private @Nullable Ref<EntityStore> trySpawnWithDirectApi(Store<EntityStore> store, Vector3d position) {
        try {
            NPCPlugin npcPlugin = npcPlugin();
            if (npcPlugin == null || !isBalloonRoleResolvable(npcPlugin)) return null;

            Pair<Ref<EntityStore>, ?> pair = npcPlugin.spawnNPC(
                    store,
                    BALLOON_ROLE_ID,
                    null,
                    position,
                    new Vector3f(0f, 0f, 0f)
            );
            if (pair == null) return null;
            Ref<EntityStore> ref = pair.left();
            if (ref == null || !ref.isValid()) return null;
            return ref;
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Balloon direct API spawn failed.");
            return null;
        }
    }

    private @Nullable Ref<EntityStore> trySpawnWithCommandFallback(Store<EntityStore> store,
                                                                    World world,
                                                                    Vector3d desiredPosition,
                                                                    @Nullable String preferredRequesterUuid) {
        if (store == null || world == null || desiredPosition == null) return null;
        long now = System.nanoTime();
        if (isFallbackCooldownActive(now)) {
            if (RrDebugFlags.verbose()) {
                logger.atInfo().log("[RR-CTF] Balloon fallback command skipped during cooldown. remaining=%ds",
                        fallbackCooldownRemainingSeconds(now));
            }
            return null;
        }

        String requesterUuid = resolveFallbackRequesterUuid(preferredRequesterUuid);
        if (requesterUuid == null) {
            recordFallbackFailure(now, null, "No online requester available for command fallback.", false);
            return null;
        }

        PlayerRef requesterRef = parsePlayerRef(requesterUuid);
        if (requesterRef == null || !requesterRef.isValid()) {
            recordFallbackFailure(now, null, "Requester is not valid for command fallback.", false);
            return null;
        }

        String lastTemplate = null;
        String lastError = null;
        for (String suffix : orderedFallbackCommandSuffixes()) {
            lastTemplate = templateLabelForSuffix(suffix);
            String command = "npc spawn " + BALLOON_ROLE_ID + suffix;

            Map<String, NpcSnapshot> before = collectNpcSnapshots(store);
            if (before == null) {
                lastError = "Unable to snapshot NPC state before fallback command.";
                continue;
            }

            try {
                CommandManager.get().handleCommand(requesterRef, command).get(2, TimeUnit.SECONDS);
            } catch (Throwable t) {
                lastError = summarizeFailure(t);
                if (RrDebugFlags.verbose()) {
                    logger.atInfo().withCause(t).log("[RR-CTF] Balloon fallback command failed. template=%s", lastTemplate);
                }
                continue;
            }

            Map<String, NpcSnapshot> after = collectNpcSnapshots(store);
            if (after == null || after.isEmpty()) {
                lastError = "Fallback command executed but NPC snapshot after spawn was unavailable.";
                continue;
            }

            NpcSnapshot created = findClosestNewNpc(before, after, desiredPosition);
            if (created != null && created.ref() != null && created.ref().isValid()) {
                recordFallbackSuccess(suffix);
                if (RrDebugFlags.verbose()) {
                    logger.atInfo().log("[RR-CTF] Balloon fallback command spawn succeeded. template=%s", lastTemplate);
                }
                return created.ref();
            }

            lastError = "Fallback command executed but no new NPC was detected.";
        }

        recordFallbackFailure(now, lastTemplate, lastError, true);
        return null;
    }

    private List<String> orderedFallbackCommandSuffixes() {
        List<String> ordered = new ArrayList<>(FALLBACK_COMMAND_SUFFIXES.size());
        String cached;
        synchronized (lock) {
            cached = cachedFallbackCommandSuffix;
        }
        if (cached != null && FALLBACK_COMMAND_SUFFIXES.contains(cached)) {
            ordered.add(cached);
        }
        for (String suffix : FALLBACK_COMMAND_SUFFIXES) {
            if (ordered.contains(suffix)) continue;
            ordered.add(suffix);
        }
        return ordered;
    }

    private void recordFallbackSuccess(String suffix) {
        synchronized (lock) {
            cachedFallbackCommandSuffix = suffix;
            fallbackCommandCooldownUntilNanos = 0L;
            lastFallbackErrorSummary = null;
            lastFallbackAttemptTemplate = templateLabelForSuffix(suffix);
        }
    }

    private void recordFallbackFailure(long nowNanos,
                                       @Nullable String attemptedTemplate,
                                       @Nullable String errorSummary,
                                       boolean applyCooldown) {
        synchronized (lock) {
            if (attemptedTemplate != null && !attemptedTemplate.isBlank()) {
                lastFallbackAttemptTemplate = attemptedTemplate;
            }
            if (errorSummary != null && !errorSummary.isBlank()) {
                lastFallbackErrorSummary = errorSummary;
            }
            cachedFallbackCommandSuffix = null;
            if (applyCooldown) {
                fallbackCommandCooldownUntilNanos = nowNanos + FALLBACK_FAILURE_COOLDOWN_NANOS;
            }
        }

        if (applyCooldown) {
            logger.atWarning().log(
                    "[RR-CTF] Balloon fallback command spawn failed. template=%s reason=%s",
                    (attemptedTemplate == null || attemptedTemplate.isBlank()) ? "<unknown>" : attemptedTemplate,
                    (errorSummary == null || errorSummary.isBlank()) ? "<unknown>" : errorSummary
            );
        } else if (RrDebugFlags.verbose()) {
            logger.atInfo().log(
                    "[RR-CTF] Balloon fallback command unavailable. template=%s reason=%s",
                    (attemptedTemplate == null || attemptedTemplate.isBlank()) ? "<unknown>" : attemptedTemplate,
                    (errorSummary == null || errorSummary.isBlank()) ? "<unknown>" : errorSummary
            );
        }
    }

    private boolean isFallbackCooldownActive(long nowNanos) {
        synchronized (lock) {
            return fallbackCommandCooldownUntilNanos > nowNanos;
        }
    }

    private long fallbackCooldownRemainingSeconds(long nowNanos) {
        synchronized (lock) {
            if (fallbackCommandCooldownUntilNanos <= nowNanos) return 0L;
            long nanosLeft = fallbackCommandCooldownUntilNanos - nowNanos;
            return (long) Math.ceil(nanosLeft / 1_000_000_000.0d);
        }
    }

    private static String templateLabelForSuffix(@Nullable String suffix) {
        if (suffix == null) return "<unresolved>";
        return "npc spawn <role>" + suffix;
    }

    private static String summarizeFailure(Throwable throwable) {
        if (throwable == null) return "<unknown>";
        Throwable root = throwable;
        if (throwable.getCause() != null) {
            root = throwable.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName() + ": " + message;
    }

    private @Nullable Map<String, NpcSnapshot> collectNpcSnapshots(Store<EntityStore> store) {
        if (store == null) return null;
        try {
            Map<String, NpcSnapshot> out = new LinkedHashMap<>();
            store.forEachChunk(NPC_QUERY, (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
                for (int index = 0; index < chunk.size(); index++) {
                    NPCEntity npc = chunk.getComponent(index, NPCEntity.getComponentType());
                    if (npc == null || npc.getUuid() == null || npc.wasRemoved()) continue;
                    Ref<EntityStore> ref = chunk.getReferenceTo(index);
                    if (ref == null || !ref.isValid()) continue;

                    TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
                    Vector3d position = (transform == null) ? null : transform.getPosition();
                    out.put(npc.getUuid().toString(), new NpcSnapshot(ref, position));
                }
            });
            return out;
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed collecting NPC snapshot for fallback spawn.");
            return null;
        }
    }

    private static @Nullable NpcSnapshot findClosestNewNpc(Map<String, NpcSnapshot> before,
                                                           Map<String, NpcSnapshot> after,
                                                           Vector3d desiredPosition) {
        if (after == null || after.isEmpty()) return null;

        NpcSnapshot best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Map.Entry<String, NpcSnapshot> entry : after.entrySet()) {
            String uuid = entry.getKey();
            NpcSnapshot snapshot = entry.getValue();
            if (uuid == null || snapshot == null || snapshot.ref() == null) continue;
            if (before != null && before.containsKey(uuid)) continue;

            Vector3d position = snapshot.position();
            if (position == null || desiredPosition == null) return snapshot;

            double dx = position.getX() - desiredPosition.getX();
            double dy = position.getY() - desiredPosition.getY();
            double dz = position.getZ() - desiredPosition.getZ();
            double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = snapshot;
            }
        }
        return best;
    }

    private void safeRemove(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        if (commandBuffer == null) return;
        if (ref == null || !ref.isValid()) return;
        try {
            commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to despawn balloon.");
        }
    }

    private boolean acquireSlice(long nowNanos) {
        synchronized (lock) {
            if (nowNanos < nextSliceAtNanos) return false;
            nextSliceAtNanos = nowNanos + PROCESS_SLICE_INTERVAL_NANOS;
            return true;
        }
    }

    private boolean isStoreForWorld(Store<EntityStore> store, World world) {
        if (store == null || world == null) return false;
        try {
            Object external = store.getExternalData();
            if (!(external instanceof EntityStore entityStore)) return false;
            World storeWorld = entityStore.getWorld();
            if (storeWorld == null) return false;
            return storeWorld.getName().equals(world.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isDirectApiReady() {
        return npcPlugin() != null;
    }

    private boolean hasFallbackRequester() {
        return resolveFallbackRequesterUuid(null) != null;
    }

    private boolean isBalloonRoleResolvable() {
        NPCPlugin npcPlugin = npcPlugin();
        return isBalloonRoleResolvable(npcPlugin);
    }

    private boolean isBalloonRoleResolvable(@Nullable NPCPlugin npcPlugin) {
        if (npcPlugin == null) return false;
        try {
            return npcPlugin.hasRoleName(BALLOON_ROLE_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private @Nullable NPCPlugin npcPlugin() {
        try {
            return NPCPlugin.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private @Nullable String resolveFallbackRequesterUuid(@Nullable String preferredUuid) {
        if (isOnlinePlayerUuid(preferredUuid)) return preferredUuid;
        if (matchService == null) return null;

        try {
            for (String uuid : matchService.getActiveMatchTeams().keySet()) {
                if (isOnlinePlayerUuid(uuid)) return uuid;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isOnlinePlayerUuid(@Nullable String uuid) {
        return parsePlayerRef(uuid) != null;
    }

    private @Nullable PlayerRef parsePlayerRef(@Nullable String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            UUID parsed = UUID.fromString(uuid);
            PlayerRef ref = Universe.get().getPlayer(parsed);
            if (ref == null || !ref.isValid()) return null;
            return ref;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void pruneInvalidRefsLocked() {
        Iterator<Ref<EntityStore>> iterator = activeBalloonsByRef.keySet().iterator();
        while (iterator.hasNext()) {
            Ref<EntityStore> ref = iterator.next();
            if (ref == null || !ref.isValid()) {
                iterator.remove();
            }
        }
    }

    private String resolveBlockingReason(CtfRegionRepository.RegionDefinition region,
                                         World resolvedRegionWorld,
                                         boolean directApiReady,
                                         boolean fallbackReady,
                                         boolean roleResolvable,
                                         int activeCount,
                                         long fallbackCooldownRemainingSeconds) {
        if (matchService == null) return "CTF match service is unavailable.";
        if (!matchService.isRunning()) return "CTF match is not running.";
        if (regionRepository == null) return "CTF region repository is unavailable.";
        if (region == null) return "CTF region is not configured.";
        if (!region.enabled()) return "CTF region is disabled.";
        if (!region.hasBounds()) return "CTF region bounds are not set.";
        if (resolvedRegionWorld == null) return "CTF region world is unavailable: " + region.worldName();
        if (!roleResolvable) return "Balloon NPC role is unavailable: " + BALLOON_ROLE_ID;
        if (!directApiReady && fallbackReady && fallbackCooldownRemainingSeconds > 0L) {
            return "Balloon command fallback cooling down (" + fallbackCooldownRemainingSeconds + "s).";
        }
        if (!directApiReady && !fallbackReady) return "No balloon spawn backend is available.";
        if (activeCount >= MAX_ACTIVE_BALLOONS) return "Active balloon cap reached (" + MAX_ACTIVE_BALLOONS + ").";
        return null;
    }

    private record RewardDecision(CtfMatchService.Team team, int place, String itemId) {
    }
}
