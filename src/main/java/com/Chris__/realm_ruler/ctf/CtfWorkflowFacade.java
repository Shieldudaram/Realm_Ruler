package com.Chris__.realm_ruler.ctf;

import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.match.CtfArmorLoadoutService;
import com.Chris__.realm_ruler.match.CtfBalloonSpawnService;
import com.Chris__.realm_ruler.match.CtfFlagStateService;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.match.CtfPointsRepository;
import com.Chris__.realm_ruler.match.CtfRegionRepository;
import com.Chris__.realm_ruler.match.CtfShopService;
import com.Chris__.realm_ruler.match.CtfStandRegistryRepository;
import com.Chris__.realm_ruler.modes.ctf.CtfRules;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.util.SpawnTeleportUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CtfWorkflowFacade {

    public static final String REGION_PERMISSION = "realmruler.ctf.region.manage";
    public static final String STAND_PERMISSION = "realmruler.ctf.stand.manage";
    public static final int DEFAULT_MATCH_MINUTES = 8;
    public static final int MIN_MATCH_MINUTES = 1;
    public static final int MAX_MATCH_MINUTES = 60;
    public static final int DEFAULT_BALLOON_COUNT = 1;
    public static final int MIN_BALLOON_COUNT = 1;
    public static final int MAX_BALLOON_COUNT = 50;
    public static final double SPAWN_JITTER_RADIUS_BLOCKS = 3.0d;

    public enum ResultCode {
        OK,
        NOT_READY,
        INVALID_INPUT,
        PLAYERS_ONLY,
        NO_PERMISSION,
        OBJECTIVE_FLAG_BLOCKED,
        ARMOR_BLOCKED,
        MATCH_RUNNING,
        MATCH_NOT_RUNNING,
        MATCH_ALREADY_RUNNING,
        JOINED_LOBBY,
        ALREADY_WAITING,
        LEFT_LOBBY,
        NOT_IN_LOBBY,
        POINTS_STATUS,
        SHOP_NOT_READY,
        SHOP_ITEM_NOT_FOUND,
        SHOP_PURCHASED,
        SHOP_PURCHASE_FAILED,
        MISSING_HOME_STANDS,
        MISSING_TEAM_SPAWNS,
        POSITION_UNAVAILABLE,
        REGION_CREATED,
        REGION_NOT_CONFIGURED,
        REGION_WORLD_MISMATCH,
        REGION_UPDATED,
        REGION_CLEARED,
        REGION_UPDATE_FAILED,
        STAND_VALIDATION_FAILED,
        STAND_ADDED,
        STAND_ALREADY_EXISTS,
        STAND_REMOVED,
        STAND_NOT_FOUND,
        STAND_PRIMARY_SET,
        BALLOON_STATUS,
        BALLOON_SPAWNED,
        BALLOON_BLOCKED
    }

    public record ActionResult(boolean success, ResultCode code, String message, String detailMessage) {
        public static ActionResult success(ResultCode code, String message) {
            return new ActionResult(true, code, safe(message), "");
        }

        public static ActionResult success(ResultCode code, String message, String detailMessage) {
            return new ActionResult(true, code, safe(message), safe(detailMessage));
        }

        public static ActionResult failure(ResultCode code, String message) {
            return new ActionResult(false, code, safe(message), "");
        }

        public static ActionResult failure(ResultCode code, String message, String detailMessage) {
            return new ActionResult(false, code, safe(message), safe(detailMessage));
        }

        public String combinedMessage() {
            if (detailMessage == null || detailMessage.isBlank()) {
                return safe(message);
            }
            if (message == null || message.isBlank()) {
                return safe(detailMessage);
            }
            return safe(message) + " " + safe(detailMessage);
        }
    }

    public record TeamCountSummary(int red, int blue, int yellow, int white) {
    }

    public record HubSnapshot(int points,
                              String currentTeam,
                              boolean matchRunning,
                              int remainingSeconds,
                              int waitingCount,
                              TeamCountSummary lobbyTeamCounts,
                              TeamCountSummary activeTeamCounts,
                              boolean canAccessAdmin,
                              boolean canManageRegion,
                              boolean canManageStands) {
    }

    public record PlaySnapshot(int points,
                               String currentTeam,
                               boolean matchRunning,
                               int waitingCount,
                               TeamCountSummary lobbyTeamCounts,
                               TeamCountSummary activeTeamCounts,
                               boolean canLeaveLobby,
                               boolean canJoinLobby,
                               ResultCode joinBlockedCode,
                               String joinBlockedReason) {
    }

    public record MatchSnapshot(int points,
                                String currentTeam,
                                boolean matchRunning,
                                int remainingSeconds,
                                int waitingCount,
                                TeamCountSummary lobbyTeamCounts,
                                TeamCountSummary activeTeamCounts,
                                boolean canAccessAdmin,
                                boolean canManageRegion,
                                boolean canManageStands) {
    }

    public record ShopSnapshot(int points,
                               List<CtfShopService.ShopItemView> visibleItems,
                               CtfShopService.ShopItemView selectedItem,
                               boolean truncated,
                               int totalItems) {
    }

    public record AdminSnapshot(boolean canAccessAdmin,
                                boolean canManageRegion,
                                boolean canManageStands,
                                boolean canManageBalloons) {
    }

    public record RegionSnapshot(boolean canManage,
                                 String worldName,
                                 boolean enabled,
                                 String pos1,
                                 String pos2,
                                 boolean ready) {
    }

    public record StandRow(String worldName,
                           int x,
                           int y,
                           int z,
                           boolean primary,
                           String label) {
    }

    public record StandsSnapshot(boolean canManage,
                                 CtfMatchService.Team selectedTeam,
                                 List<StandRow> visibleRows,
                                 boolean truncated,
                                 int totalCount) {
    }

    public record BalloonsSnapshot(boolean canManage,
                                   CtfBalloonSpawnService.StatusSnapshot status) {
    }

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final TargetingService targetingService;
    private final CtfFlagStateService flagStateService;
    private final CtfStandRegistryRepository standRegistry;
    private final CtfPointsRepository pointsRepository;
    private final CtfShopService shopService;
    private final CtfBalloonSpawnService balloonSpawnService;
    private final CtfRegionRepository regionRepository;
    private final CtfArmorLoadoutService armorLoadoutService;

    public CtfWorkflowFacade(CtfMatchService matchService,
                             SimpleClaimsCtfBridge simpleClaims,
                             TargetingService targetingService,
                             CtfFlagStateService flagStateService,
                             CtfStandRegistryRepository standRegistry,
                             CtfPointsRepository pointsRepository,
                             CtfShopService shopService,
                             CtfBalloonSpawnService balloonSpawnService,
                             CtfRegionRepository regionRepository,
                             CtfArmorLoadoutService armorLoadoutService) {
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.targetingService = targetingService;
        this.flagStateService = flagStateService;
        this.standRegistry = standRegistry;
        this.pointsRepository = pointsRepository;
        this.shopService = shopService;
        this.balloonSpawnService = balloonSpawnService;
        this.regionRepository = regionRepository;
        this.armorLoadoutService = armorLoadoutService;
    }

    public HubSnapshot snapshotHub(Player player, String uuid) {
        int points = getPoints(uuid);
        String currentTeam = currentTeamName(uuid);
        boolean running = matchService != null && matchService.isRunning();
        int remainingSeconds = (matchService == null) ? 0 : Math.max(0, matchService.getRemainingSeconds());
        Map<String, CtfMatchService.Team> lobbyTeams = lobbyTeamsSnapshot();
        Map<String, CtfMatchService.Team> activeTeams = activeTeamsSnapshot();
        return new HubSnapshot(
                points,
                safe(currentTeam, "None"),
                running,
                remainingSeconds,
                lobbyTeams.size(),
                teamCounts(lobbyTeams),
                teamCounts(activeTeams),
                canAccessAdmin(player),
                canManageRegion(player),
                canManageStands(player)
        );
    }

    public PlaySnapshot snapshotPlay(Player player, String uuid) {
        Map<String, CtfMatchService.Team> lobbyTeams = lobbyTeamsSnapshot();
        Map<String, CtfMatchService.Team> activeTeams = activeTeamsSnapshot();
        boolean running = matchService != null && matchService.isRunning();
        ActionResult joinAvailability = (player == null || uuid == null || uuid.isBlank())
                ? ActionResult.success(ResultCode.OK, "")
                : previewJoinLobby(player, uuid);
        return new PlaySnapshot(
                getPoints(uuid),
                safe(currentTeamName(uuid), "None"),
                running,
                lobbyTeams.size(),
                teamCounts(lobbyTeams),
                teamCounts(activeTeams),
                matchService != null && !running && uuid != null && !uuid.isBlank() && matchService.lobbyTeamFor(uuid) != null,
                joinAvailability != null && joinAvailability.success(),
                (joinAvailability == null || joinAvailability.success()) ? ResultCode.OK : joinAvailability.code(),
                (joinAvailability == null || joinAvailability.success()) ? "" : joinAvailability.message()
        );
    }

    public MatchSnapshot snapshotMatch(Player player, String uuid) {
        Map<String, CtfMatchService.Team> lobbyTeams = lobbyTeamsSnapshot();
        Map<String, CtfMatchService.Team> activeTeams = activeTeamsSnapshot();
        boolean running = matchService != null && matchService.isRunning();
        return new MatchSnapshot(
                getPoints(uuid),
                safe(currentTeamName(uuid), "None"),
                running,
                (matchService == null) ? 0 : Math.max(0, matchService.getRemainingSeconds()),
                lobbyTeams.size(),
                teamCounts(lobbyTeams),
                teamCounts(activeTeams),
                canAccessAdmin(player),
                canManageRegion(player),
                canManageStands(player)
        );
    }

    public ShopSnapshot snapshotShop(String uuid, String selectedItemId, int maxVisible) {
        if (shopService == null) {
            return new ShopSnapshot(getPoints(uuid), List.of(), null, false, 0);
        }

        shopService.reloadCatalog();
        List<CtfShopService.ShopItemView> allItems = safeItemList(shopService.listEnabledItems());
        List<CtfShopService.ShopItemView> visibleItems = limitCopy(allItems, Math.max(0, maxVisible));
        CtfShopService.ShopItemView selectedItem = null;
        if (selectedItemId != null && !selectedItemId.isBlank()) {
            for (CtfShopService.ShopItemView item : visibleItems) {
                if (item != null && item.id() != null && item.id().equalsIgnoreCase(selectedItemId.trim())) {
                    selectedItem = item;
                    break;
                }
            }
        }
        if (selectedItem == null && !visibleItems.isEmpty()) {
            selectedItem = visibleItems.getFirst();
        }
        return new ShopSnapshot(getPoints(uuid), visibleItems, selectedItem, allItems.size() > visibleItems.size(), allItems.size());
    }

    public AdminSnapshot snapshotAdmin(Player player) {
        boolean region = canManageRegion(player);
        boolean stands = canManageStands(player);
        return new AdminSnapshot(region || stands, region, stands, region);
    }

    public RegionSnapshot snapshotRegion(boolean canManage) {
        CtfRegionRepository.RegionDefinition region = (regionRepository == null) ? null : regionRepository.get();
        if (region == null) {
            return new RegionSnapshot(canManage, "<unset>", false, "<unset>", "<unset>", false);
        }
        return new RegionSnapshot(
                canManage,
                safe(region.worldName(), "<unset>"),
                region.enabled(),
                formatPos(region.pos1()),
                formatPos(region.pos2()),
                region.hasBounds() && region.enabled()
        );
    }

    public StandsSnapshot snapshotStands(boolean canManage, CtfMatchService.Team selectedTeam, int maxVisible) {
        CtfMatchService.Team effectiveTeam = (selectedTeam == null) ? CtfMatchService.Team.RED : selectedTeam;
        List<CtfStandRegistryRepository.StandLocation> stands = listStands(effectiveTeam);
        List<StandRow> visibleRows = new ArrayList<>();
        int visibleCount = Math.min(Math.max(0, maxVisible), stands.size());
        for (int index = 0; index < visibleCount; index++) {
            CtfStandRegistryRepository.StandLocation stand = stands.get(index);
            if (stand == null) continue;
            boolean primary = index == 0;
            visibleRows.add(new StandRow(
                    stand.worldName(),
                    stand.x(),
                    stand.y(),
                    stand.z(),
                    primary,
                    (primary ? "Primary " : "Stand ") + stand.worldName() + " " + stand.x() + " " + stand.y() + " " + stand.z()
            ));
        }
        return new StandsSnapshot(canManage, effectiveTeam, List.copyOf(visibleRows), stands.size() > visibleRows.size(), stands.size());
    }

    public BalloonsSnapshot snapshotBalloons(boolean canManage) {
        return new BalloonsSnapshot(canManage, (balloonSpawnService == null) ? null : balloonSpawnService.statusSnapshot());
    }

    public ActionResult joinLobby(Player player, String uuid, CtfMatchService.Team requestedTeam) {
        ActionResult joinAvailability = previewJoinLobby(player, uuid);
        if (joinAvailability != null && !joinAvailability.success()) {
            return joinAvailability;
        }

        CtfMatchService.Team previous = matchService.lobbyTeamFor(uuid);
        CtfMatchService.JoinLobbyResult result = matchService.joinLobby(uuid, requestedTeam);
        if (result.status() == CtfMatchService.JoinStatus.NOT_READY) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }

        String teamName = safeTeamName(result.team());
        if (result.status() == CtfMatchService.JoinStatus.MATCH_RUNNING) {
            String detail = "";
            if (!teamName.equals("<unknown>") && simpleClaims != null) {
                simpleClaims.setTeam(uuid, teamName);
                if (targetingService != null) {
                    SimpleClaimsCtfBridge.TeamSpawn spawn = simpleClaims.getTeamSpawn(teamName);
                    if (spawn != null) {
                        SpawnTeleportUtil.queueTeamSpawnTeleport(
                                targetingService,
                                uuid,
                                spawn.world(),
                                spawn.x(),
                                spawn.y(),
                                spawn.z(),
                                SPAWN_JITTER_RADIUS_BLOCKS
                        );
                    } else {
                        detail = "Team spawn not set for: " + teamName + ". Ask an admin to run: /sc "
                                + teamName.toLowerCase(Locale.ROOT) + " spawn";
                    }
                }
            }
            return ActionResult.failure(
                    ResultCode.MATCH_RUNNING,
                    "Capture The Flag match already running (remaining: "
                            + formatSeconds(matchService.getRemainingSeconds()) + "). Team: " + teamName,
                    detail
            );
        }

        if ((result.status() == CtfMatchService.JoinStatus.JOINED
                || result.status() == CtfMatchService.JoinStatus.ALREADY_WAITING)
                && armorLoadoutService != null
                && result.team() != null) {
            CtfArmorLoadoutService.EquipResult equipResult = armorLoadoutService.equipTeamArmor(uuid, player, result.team());
            if (!equipResult.success()) {
                if (result.status() == CtfMatchService.JoinStatus.JOINED) {
                    matchService.leaveLobby(uuid);
                } else if (previous != null) {
                    matchService.joinLobby(uuid, previous);
                } else {
                    matchService.leaveLobby(uuid);
                }
                return ActionResult.failure(ResultCode.ARMOR_BLOCKED, equipResult.message());
            }
        }

        if (result.status() == CtfMatchService.JoinStatus.ALREADY_WAITING) {
            boolean changed = requestedTeam != null && previous != null && requestedTeam != previous;
            return ActionResult.success(
                    ResultCode.ALREADY_WAITING,
                    changed
                            ? "Switched to team: " + teamName + " (waiting: " + result.waitingCount() + ")"
                            : "You're already waiting in the Capture The Flag lobby. Team: "
                            + teamName + " (waiting: " + result.waitingCount() + ")"
            );
        }

        return ActionResult.success(
                ResultCode.JOINED_LOBBY,
                "Joined Capture The Flag lobby. Team: " + teamName + " (waiting: " + result.waitingCount() + ")"
        );
    }

    public ActionResult previewJoinLobby(Player player, String uuid) {
        if (matchService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        if (player == null || uuid == null || uuid.isBlank()) {
            return ActionResult.failure(ResultCode.PLAYERS_ONLY, "Players only.");
        }
        if (containsObjectiveFlag(player)) {
            return ActionResult.failure(
                    ResultCode.OBJECTIVE_FLAG_BLOCKED,
                    "You cannot join Capture The Flag while carrying objective flags. Put them away first."
            );
        }
        if (!matchService.isRunning()
                && armorLoadoutService != null
                && !armorLoadoutService.canJoinWithCurrentArmor(uuid, player)) {
            return ActionResult.failure(ResultCode.ARMOR_BLOCKED, "Remove your current armor before joining Capture The Flag.");
        }
        return ActionResult.success(ResultCode.OK, "");
    }

    public ActionResult leaveLobby(Player player, String uuid) {
        if (matchService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        if (player == null || uuid == null || uuid.isBlank()) {
            return ActionResult.failure(ResultCode.PLAYERS_ONLY, "Players only.");
        }
        if (matchService.isRunning()) {
            return ActionResult.failure(ResultCode.MATCH_RUNNING, "Can't leave the lobby while a match is running.");
        }

        boolean removed = matchService.leaveLobby(uuid);
        if (removed && armorLoadoutService != null) {
            armorLoadoutService.restoreForParticipant(uuid, player);
        }
        return removed
                ? ActionResult.success(ResultCode.LEFT_LOBBY, "Left Capture The Flag lobby.")
                : ActionResult.failure(ResultCode.NOT_IN_LOBBY, "You're not in the Capture The Flag lobby.");
    }

    public ActionResult pointsStatus(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return ActionResult.failure(ResultCode.PLAYERS_ONLY, "Players only.");
        }
        return ActionResult.success(ResultCode.POINTS_STATUS, "Capture The Flag points: " + getPoints(uuid));
    }

    public ActionResult startMatch(int minutes) {
        if (matchService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }

        int clampedMinutes = clampMatchMinutes(minutes);
        Map<String, CtfMatchService.Team> lobby = lobbyTeamsSnapshot();
        Set<CtfMatchService.Team> activeTeams = new HashSet<>(lobby.values());
        activeTeams.remove(null);

        if (flagStateService != null) {
            Map<CtfMatchService.Team, String> missingReasons = flagStateService.missingHomeTeamReasons(activeTeams);
            if (!missingReasons.isEmpty()) {
                List<String> detail = new ArrayList<>();
                for (Map.Entry<CtfMatchService.Team, String> entry : missingReasons.entrySet()) {
                    if (entry.getKey() == null) continue;
                    String reason = safe(entry.getValue(), "unknown reason");
                    detail.add(entry.getKey().displayName() + " (" + reason + ")");
                }
                return ActionResult.failure(
                        ResultCode.MISSING_HOME_STANDS,
                        "Missing valid home stands for: " + String.join(", ", detail),
                        "Register them from the Stands page or with /rr ctf stand add <team> <world> <x> <y> <z>."
                );
            }
        }

        if (simpleClaims != null && simpleClaims.isAvailable()) {
            List<String> missing = new ArrayList<>();
            for (CtfMatchService.Team team : activeTeams) {
                if (team == null) continue;
                if (simpleClaims.getTeamSpawn(team.displayName()) == null) {
                    missing.add(team.displayName());
                }
            }
            if (!missing.isEmpty()) {
                return ActionResult.failure(
                        ResultCode.MISSING_TEAM_SPAWNS,
                        "Missing spawns for: " + String.join(", ", missing),
                        "Set them with: /sc red spawn, /sc blue spawn, /sc yellow spawn, /sc white spawn"
                );
            }
        }

        int seconds = clampedMinutes * 60;
        CtfMatchService.StartResult result = matchService.startCaptureTheFlag(seconds);
        if (result == CtfMatchService.StartResult.STARTED) {
            if (flagStateService != null) {
                flagStateService.resetForNewMatch();
            }
            if (simpleClaims != null) {
                Map<String, String> teamNameByUuid = new HashMap<>();
                for (Map.Entry<String, CtfMatchService.Team> entry : matchService.getActiveMatchTeams().entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    teamNameByUuid.put(entry.getKey(), entry.getValue().displayName());
                }
                simpleClaims.ensureParties();
                simpleClaims.applyTeams(teamNameByUuid);

                if (targetingService != null) {
                    for (Map.Entry<String, CtfMatchService.Team> entry : matchService.getActiveMatchTeams().entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null) continue;
                        SimpleClaimsCtfBridge.TeamSpawn spawn = simpleClaims.getTeamSpawn(entry.getValue().displayName());
                        if (spawn == null) continue;
                        SpawnTeleportUtil.queueTeamSpawnTeleport(
                                targetingService,
                                entry.getKey(),
                                spawn.world(),
                                spawn.x(),
                                spawn.y(),
                                spawn.z(),
                                SPAWN_JITTER_RADIUS_BLOCKS
                        );
                    }
                }
            }
            return ActionResult.success(ResultCode.OK, "Started Capture The Flag match timer: " + formatSeconds(seconds));
        }
        if (result == CtfMatchService.StartResult.ALREADY_RUNNING) {
            return ActionResult.failure(
                    ResultCode.MATCH_ALREADY_RUNNING,
                    "Capture The Flag match already running (remaining: " + formatSeconds(matchService.getRemainingSeconds()) + ")"
            );
        }
        return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
    }

    public ActionResult stopMatch() {
        if (matchService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        if (!matchService.isRunning()) {
            return ActionResult.failure(ResultCode.MATCH_NOT_RUNNING, "No Capture The Flag match is running.");
        }
        matchService.stopCaptureTheFlag();
        return ActionResult.success(ResultCode.OK, "Stopping Capture The Flag match...");
    }

    public List<CtfShopService.ShopItemView> listEnabledShopItems() {
        if (shopService == null) return List.of();
        shopService.reloadCatalog();
        return safeItemList(shopService.listEnabledItems());
    }

    public CtfShopService.ShopItemView describeShopItem(String itemId) {
        if (shopService == null) return null;
        shopService.reloadCatalog();
        return shopService.describeItem(itemId);
    }

    public ActionResult purchaseShopItem(Player player, String uuid, String itemId) {
        if (shopService == null) {
            return ActionResult.failure(ResultCode.SHOP_NOT_READY, "Capture The Flag shop is not ready yet.");
        }
        if (player == null || uuid == null || uuid.isBlank()) {
            return ActionResult.failure(ResultCode.PLAYERS_ONLY, "Players only.");
        }
        shopService.reloadCatalog();
        CtfShopService.PurchaseResult result = shopService.purchase(player, uuid, itemId);
        if (result.success()) {
            String detail = (result.remainingPoints() >= 0)
                    ? "Remaining points: " + result.remainingPoints()
                    : "";
            return ActionResult.success(ResultCode.SHOP_PURCHASED, result.message(), detail);
        }
        ResultCode code = (result.item() == null) ? ResultCode.SHOP_ITEM_NOT_FOUND : ResultCode.SHOP_PURCHASE_FAILED;
        return ActionResult.failure(code, result.message());
    }

    public ActionResult createRegion(boolean hasPermission, String worldName) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + REGION_PERMISSION);
        }
        if (regionRepository == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        String safeWorld = safe(worldName, "");
        if (safeWorld.isBlank()) {
            return ActionResult.failure(ResultCode.INVALID_INPUT, "World not found: " + safe(worldName));
        }
        World world = Universe.get().getWorld(safeWorld);
        if (world == null) {
            return ActionResult.failure(ResultCode.INVALID_INPUT, "World not found: " + safeWorld);
        }
        regionRepository.create(world.getName());
        return ActionResult.success(
                ResultCode.REGION_CREATED,
                "Capture The Flag region created for world '" + world.getName() + "'.",
                "Set Pos1 Here and Set Pos2 Here to finish the region."
        );
    }

    public ActionResult createRegionHere(boolean hasPermission, String uuid) {
        if (targetingService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        TargetingService.PlayerLocationSnapshot snapshot = targetingService.getLatestPlayerLocation(uuid);
        if (snapshot == null || !snapshot.isValid()) {
            return ActionResult.failure(ResultCode.POSITION_UNAVAILABLE, "Could not resolve your current position. Try moving and run again.");
        }
        String worldName = safe(snapshot.worldName(), "");
        if (worldName.isBlank()) {
            return ActionResult.failure(ResultCode.POSITION_UNAVAILABLE, "Could not resolve your current world. Try moving and run again.");
        }
        return createRegion(hasPermission, worldName);
    }

    public ActionResult setRegionPosHere(boolean hasPermission, String uuid, boolean first) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + REGION_PERMISSION);
        }
        if (regionRepository == null || targetingService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }

        CtfRegionRepository.RegionDefinition region = regionRepository.get();
        if (region == null) {
            return ActionResult.failure(ResultCode.REGION_NOT_CONFIGURED, "Create the region first.");
        }

        TargetingService.PlayerLocationSnapshot snapshot = targetingService.getLatestPlayerLocation(uuid);
        if (snapshot == null || !snapshot.isValid()) {
            return ActionResult.failure(ResultCode.POSITION_UNAVAILABLE, "Could not resolve your current position. Try moving and run again.");
        }
        if (!safe(region.worldName(), "").equals(safe(snapshot.worldName(), ""))) {
            return ActionResult.failure(ResultCode.REGION_WORLD_MISMATCH, "You must be in region world: " + safe(region.worldName(), "<unset>"));
        }

        CtfRegionRepository.BlockPos pos = new CtfRegionRepository.BlockPos(
                (int) Math.floor(snapshot.x()),
                (int) Math.floor(snapshot.y()),
                (int) Math.floor(snapshot.z())
        );
        boolean updated = first ? regionRepository.setPos1(pos) : regionRepository.setPos2(pos);
        if (!updated) {
            return ActionResult.failure(ResultCode.REGION_UPDATE_FAILED, "Failed to set region " + (first ? "pos1" : "pos2") + ".");
        }
        return ActionResult.success(
                ResultCode.REGION_UPDATED,
                "Set Capture The Flag region " + (first ? "pos1" : "pos2") + " to " + pos.x() + " " + pos.y() + " " + pos.z()
        );
    }

    public ActionResult clearRegion(boolean hasPermission) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + REGION_PERMISSION);
        }
        if (regionRepository == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        boolean cleared = regionRepository.clear();
        return cleared
                ? ActionResult.success(ResultCode.REGION_CLEARED, "Cleared Capture The Flag region.")
                : ActionResult.failure(ResultCode.REGION_NOT_CONFIGURED, "No Capture The Flag region was configured.");
    }

    public List<CtfStandRegistryRepository.StandLocation> listStands(CtfMatchService.Team team) {
        if (standRegistry == null || team == null) return List.of();
        return standRegistry.getOrderedStands(team);
    }

    public ActionResult addStand(boolean hasPermission,
                                 CtfMatchService.Team team,
                                 String worldName,
                                 int x,
                                 int y,
                                 int z) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + STAND_PERMISSION);
        }
        if (standRegistry == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        if (team == null) {
            return ActionResult.failure(ResultCode.INVALID_INPUT, "Choose a team first.");
        }

        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return ActionResult.failure(ResultCode.POSITION_UNAVAILABLE, "World not found: " + worldName);
        }

        ActionResult validation = validateStandLocation(team, worldName, x, z);
        if (!validation.success()) {
            return validation;
        }

        CtfStandRegistryRepository.StandLocation stand = new CtfStandRegistryRepository.StandLocation(worldName, x, y, z);
        boolean added = standRegistry.addStand(team, stand);
        return added
                ? ActionResult.success(ResultCode.STAND_ADDED,
                "Added stand for " + team.displayName() + ": " + worldName + " " + x + " " + y + " " + z)
                : ActionResult.failure(ResultCode.STAND_ALREADY_EXISTS,
                "Stand already registered for " + team.displayName() + ".");
    }

    public ActionResult addStandHere(boolean hasPermission, String uuid, CtfMatchService.Team team) {
        if (targetingService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        TargetingService.PlayerLocationSnapshot snapshot = targetingService.getLatestPlayerLocation(uuid);
        if (snapshot == null || !snapshot.isValid()) {
            return ActionResult.failure(ResultCode.POSITION_UNAVAILABLE, "Could not resolve your current position. Try moving and run again.");
        }
        String worldName = safe(snapshot.worldName(), "");
        return addStand(
                hasPermission,
                team,
                worldName,
                (int) Math.floor(snapshot.x()),
                (int) Math.floor(snapshot.y()),
                (int) Math.floor(snapshot.z())
        );
    }

    public ActionResult removeStand(boolean hasPermission,
                                    CtfMatchService.Team team,
                                    String worldName,
                                    int x,
                                    int y,
                                    int z) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + STAND_PERMISSION);
        }
        if (standRegistry == null || team == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        CtfStandRegistryRepository.StandLocation stand = new CtfStandRegistryRepository.StandLocation(worldName, x, y, z);
        boolean removed = standRegistry.removeStand(team, stand);
        return removed
                ? ActionResult.success(ResultCode.STAND_REMOVED,
                "Removed stand for " + team.displayName() + ": " + worldName + " " + x + " " + y + " " + z)
                : ActionResult.failure(ResultCode.STAND_NOT_FOUND, "Stand not found for " + team.displayName() + ".");
    }

    public ActionResult setPrimaryStand(boolean hasPermission,
                                        CtfMatchService.Team team,
                                        String worldName,
                                        int x,
                                        int y,
                                        int z) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + STAND_PERMISSION);
        }
        if (standRegistry == null || team == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        CtfStandRegistryRepository.StandLocation stand = new CtfStandRegistryRepository.StandLocation(worldName, x, y, z);
        boolean primarySet = standRegistry.setPrimaryStand(team, stand);
        return primarySet
                ? ActionResult.success(ResultCode.STAND_PRIMARY_SET, "Updated primary stand for " + team.displayName() + ".")
                : ActionResult.failure(ResultCode.STAND_NOT_FOUND, "Stand not found; add it first before setting primary.");
    }

    public ActionResult balloonStatus(boolean hasPermission) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + REGION_PERMISSION);
        }
        if (balloonSpawnService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        CtfBalloonSpawnService.StatusSnapshot status = balloonSpawnService.statusSnapshot();
        String remaining = (status.secondsUntilNextSpawn() < 0) ? "<none>" : status.secondsUntilNextSpawn() + "s";
        String message = "Balloons: running=" + status.matchRunning()
                + ", regionConfigured=" + status.regionConfigured()
                + ", regionReady=" + status.regionReady()
                + ", regionWorld=" + safe(status.regionWorldName(), "<unset>")
                + ", worldResolved=" + status.regionWorldResolved()
                + ", active=" + status.activeCount() + "/" + status.maxActive()
                + ", nextSpawn=" + remaining
                + ", roleReady=" + status.balloonRoleResolvable()
                + ", directApiReady=" + status.directApiReady()
                + ", fallbackReady=" + status.fallbackReady()
                + ", fallbackTemplate=" + safe(status.selectedFallbackTemplate(), "<unresolved>")
                + ", fallbackCooldown=" + status.fallbackCooldownRemainingSeconds() + "s";

        String detail = "";
        if (status.lastFallbackError() != null && !status.lastFallbackError().isBlank()) {
            detail = "Balloon fallback lastError=" + status.lastFallbackError()
                    + ", lastTemplate=" + safe(status.lastFallbackAttemptTemplate(), "<unknown>");
        } else if (status.blockingReason() != null && !status.blockingReason().isBlank()) {
            detail = "Balloon blocker: " + status.blockingReason();
        }
        return ActionResult.success(ResultCode.BALLOON_STATUS, message, detail);
    }

    public ActionResult spawnBalloons(boolean hasPermission, String requesterUuid, int count) {
        if (!hasPermission) {
            return ActionResult.failure(ResultCode.NO_PERMISSION, "Missing permission: " + REGION_PERMISSION);
        }
        if (balloonSpawnService == null) {
            return ActionResult.failure(ResultCode.NOT_READY, "Not ready yet (plugin still starting?).");
        }
        int clampedCount = clampBalloonCount(count);
        CtfBalloonSpawnService.SpawnNowResult result = balloonSpawnService.spawnNow(clampedCount, requesterUuid);
        ResultCode code = (result.spawnedCount() > 0) ? ResultCode.BALLOON_SPAWNED : ResultCode.BALLOON_BLOCKED;
        return new ActionResult(
                result.spawnedCount() > 0,
                code,
                "Balloon spawn result: requested=" + result.requestedCount()
                        + ", spawned=" + result.spawnedCount()
                        + ", active=" + result.activeCountAfter()
                        + ", reason=" + safe(result.message(), "ok"),
                ""
        );
    }

    public boolean canManageRegion(Player player) {
        return player != null && player.hasPermission(REGION_PERMISSION);
    }

    public boolean canManageStands(Player player) {
        return player != null && player.hasPermission(STAND_PERMISSION);
    }

    public boolean canAccessAdmin(Player player) {
        return canManageRegion(player) || canManageStands(player);
    }

    public int getPoints(String uuid) {
        if (pointsRepository != null) {
            return pointsRepository.getPoints(uuid);
        }
        if (shopService != null) {
            return shopService.getPoints(uuid);
        }
        return 0;
    }

    public static int clampMatchMinutes(int value) {
        return Math.max(MIN_MATCH_MINUTES, Math.min(MAX_MATCH_MINUTES, value));
    }

    public static int clampBalloonCount(int value) {
        return Math.max(MIN_BALLOON_COUNT, Math.min(MAX_BALLOON_COUNT, value));
    }

    public static String formatShopTags(CtfShopService.ShopItemView item) {
        if (item == null) return "";
        String availability = safe(item.availability(), "any");
        String teamRule = safe(item.teamRule(), "any");
        String team = safe(item.team(), "any");
        return " [" + availability + ", " + teamRule + ("any".equalsIgnoreCase(team) ? "" : (":" + team)) + "]";
    }

    public static String formatRewards(CtfShopService.ShopItemView item) {
        if (item == null || item.rewards() == null || item.rewards().isEmpty()) {
            return "<none>";
        }
        StringBuilder builder = new StringBuilder();
        for (CtfShopService.RewardSpec reward : item.rewards()) {
            if (reward == null || reward.itemId() == null || reward.itemId().isBlank()) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(reward.itemId()).append(" x").append(Math.max(1, reward.amount()));
        }
        return (builder.length() == 0) ? "<none>" : builder.toString();
    }

    public static String teamCountsLine(Map<String, CtfMatchService.Team> teamsByUuid) {
        TeamCountSummary counts = teamCounts(teamsByUuid);
        return "Red:" + counts.red()
                + " Blue:" + counts.blue()
                + " Yellow:" + counts.yellow()
                + " White:" + counts.white();
    }

    public static TeamCountSummary teamCounts(Map<String, CtfMatchService.Team> teamsByUuid) {
        int red = 0;
        int blue = 0;
        int yellow = 0;
        int white = 0;
        if (teamsByUuid != null) {
            for (CtfMatchService.Team team : teamsByUuid.values()) {
                if (team == null) continue;
                switch (team) {
                    case RED -> red++;
                    case BLUE -> blue++;
                    case YELLOW -> yellow++;
                    case WHITE -> white++;
                }
            }
        }
        return new TeamCountSummary(red, blue, yellow, white);
    }

    public static String formatSeconds(int totalSeconds) {
        int safeSeconds = Math.max(0, totalSeconds);
        int minutes = safeSeconds / 60;
        int seconds = safeSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private ActionResult validateStandLocation(CtfMatchService.Team team, String worldName, int x, int z) {
        if (simpleClaims == null || !simpleClaims.isAvailable()) {
            return ActionResult.success(ResultCode.OK, "");
        }

        int chunkX = ChunkUtil.chunkCoordinate(x);
        int chunkZ = ChunkUtil.chunkCoordinate(z);
        String ownerTeamRaw = simpleClaims.getTeamForChunk(worldName, chunkX, chunkZ);
        CtfMatchService.Team ownerTeamParsed = CtfMatchService.parseTeamLoose(ownerTeamRaw);
        if (ownerTeamParsed == team) {
            return ActionResult.success(ResultCode.OK, "");
        }

        String parsedName = (ownerTeamParsed == null) ? "<null>" : ownerTeamParsed.displayName();
        String rawName = safe(ownerTeamRaw, "<null>");
        return ActionResult.failure(
                ResultCode.STAND_VALIDATION_FAILED,
                "Stand location must be inside " + team.displayName()
                        + " team-claimed chunks. world=" + worldName
                        + " chunk=" + chunkX + "," + chunkZ
                        + " ownerRaw=" + rawName
                        + " ownerParsed=" + parsedName
                        + " expected=" + team.displayName()
        );
    }

    private String currentTeamName(String uuid) {
        if (uuid == null || uuid.isBlank() || matchService == null) {
            return null;
        }
        CtfMatchService.Team activeTeam = matchService.activeMatchTeamFor(uuid);
        if (activeTeam != null) return activeTeam.displayName();
        CtfMatchService.Team lobbyTeam = matchService.lobbyTeamFor(uuid);
        return (lobbyTeam == null) ? null : lobbyTeam.displayName();
    }

    private Map<String, CtfMatchService.Team> lobbyTeamsSnapshot() {
        return (matchService == null) ? Map.of() : matchService.getLobbyWaitingTeamsSnapshot();
    }

    private Map<String, CtfMatchService.Team> activeTeamsSnapshot() {
        return (matchService == null) ? Map.of() : matchService.getActiveMatchTeams();
    }

    private static List<CtfShopService.ShopItemView> safeItemList(List<CtfShopService.ShopItemView> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<CtfShopService.ShopItemView> out = new ArrayList<>();
        for (CtfShopService.ShopItemView item : items) {
            if (item != null) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private static <T> List<T> limitCopy(List<T> items, int maxVisible) {
        if (items == null || items.isEmpty() || maxVisible <= 0) return List.of();
        int visibleCount = Math.min(items.size(), maxVisible);
        return List.copyOf(items.subList(0, visibleCount));
    }

    private static String formatPos(CtfRegionRepository.BlockPos pos) {
        if (pos == null) return "<unset>";
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    private static String safeTeamName(CtfMatchService.Team team) {
        return (team == null) ? "<unknown>" : team.displayName();
    }

    private static boolean containsObjectiveFlag(Player player) {
        if (player == null) return false;
        Inventory inventory = player.getInventory();
        if (inventory == null) return false;

        return containsObjectiveFlag(inventory.getHotbar())
                || containsObjectiveFlag(inventory.getStorage())
                || containsObjectiveFlag(inventory.getBackpack())
                || containsObjectiveFlag(inventory.getTools())
                || containsObjectiveFlag(inventory.getUtility())
                || containsObjectiveFlag(inventory.getArmor());
    }

    private static boolean containsObjectiveFlag(ItemContainer container) {
        if (container == null) return false;
        final boolean[] found = new boolean[]{false};
        container.forEach((slot, stack) -> {
            if (found[0]) return;
            if (stack == null || stack.getItemId() == null) return;
            if (CtfRules.isCustomFlagId(stack.getItemId())) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static String safe(String text) {
        return (text == null) ? "" : text.trim();
    }

    private static String safe(String text, String fallback) {
        String value = safe(text);
        return value.isBlank() ? safe(fallback) : value;
    }
}
