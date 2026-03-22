package com.Chris__.realm_ruler.ctf;

import com.Chris__.realm_ruler.match.CtfFlagStateService;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.match.CtfShopService;
import com.Chris__.realm_ruler.match.CtfStandRegistryRepository;
import com.Chris__.realm_ruler.modes.CtfMode;
import com.Chris__.realm_ruler.modes.ctf.CtfState;
import com.Chris__.realm_ruler.targeting.TargetingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CtfWorkflowFacadeTest {

    @TempDir
    Path tempDir;

    @Test
    void clampMatchMinutesKeepsConfiguredBounds() {
        assertEquals(CtfWorkflowFacade.MIN_MATCH_MINUTES, CtfWorkflowFacade.clampMatchMinutes(0));
        assertEquals(8, CtfWorkflowFacade.clampMatchMinutes(8));
        assertEquals(CtfWorkflowFacade.MAX_MATCH_MINUTES, CtfWorkflowFacade.clampMatchMinutes(99));
    }

    @Test
    void clampBalloonCountKeepsConfiguredBounds() {
        assertEquals(CtfWorkflowFacade.MIN_BALLOON_COUNT, CtfWorkflowFacade.clampBalloonCount(0));
        assertEquals(7, CtfWorkflowFacade.clampBalloonCount(7));
        assertEquals(CtfWorkflowFacade.MAX_BALLOON_COUNT, CtfWorkflowFacade.clampBalloonCount(99));
    }

    @Test
    void teamCountsLineUsesStableDisplayOrder() {
        Map<String, CtfMatchService.Team> teams = Map.of(
                "u1", CtfMatchService.Team.BLUE,
                "u2", CtfMatchService.Team.RED,
                "u3", CtfMatchService.Team.BLUE,
                "u4", CtfMatchService.Team.WHITE
        );

        assertEquals("Red:1 Blue:2 Yellow:0 White:1", CtfWorkflowFacade.teamCountsLine(teams));
    }

    @Test
    void teamCountsBuildStructuredTotalsForMainMenuColumns() {
        Map<String, CtfMatchService.Team> teams = Map.of(
                "u1", CtfMatchService.Team.WHITE,
                "u2", CtfMatchService.Team.RED,
                "u3", CtfMatchService.Team.YELLOW,
                "u4", CtfMatchService.Team.YELLOW,
                "u5", CtfMatchService.Team.BLUE
        );

        CtfWorkflowFacade.TeamCountSummary counts = CtfWorkflowFacade.teamCounts(teams);
        assertEquals(1, counts.red());
        assertEquals(1, counts.blue());
        assertEquals(2, counts.yellow());
        assertEquals(1, counts.white());
    }

    @Test
    void formatRewardsUsesBundleSummary() {
        CtfShopService.ShopItemView item = new CtfShopService.ShopItemView(
                "kit_archer",
                "Archer Kit",
                8,
                "any",
                "any",
                "",
                List.of(
                        new CtfShopService.RewardSpec("Bow", 1),
                        new CtfShopService.RewardSpec("Arrow", 32)
                )
        );

        assertEquals("Bow x1, Arrow x32", CtfWorkflowFacade.formatRewards(item));
    }

    @Test
    void snapshotStandsTruncatesToVisibleRows() {
        CtfStandRegistryRepository repository = new CtfStandRegistryRepository(tempDir, null);
        for (int index = 0; index < 10; index++) {
            repository.addStand(
                    CtfMatchService.Team.RED,
                    new CtfStandRegistryRepository.StandLocation("test_world", index, 64, index)
            );
        }

        CtfWorkflowFacade facade = new CtfWorkflowFacade(
                null,
                null,
                null,
                null,
                repository,
                null,
                null,
                null,
                null,
                null
        );

        CtfWorkflowFacade.StandsSnapshot snapshot = facade.snapshotStands(true, CtfMatchService.Team.RED, 8);
        assertTrue(snapshot.canManage());
        assertEquals(CtfMatchService.Team.RED, snapshot.selectedTeam());
        assertEquals(8, snapshot.visibleRows().size());
        assertEquals(10, snapshot.totalCount());
        assertTrue(snapshot.truncated());
        assertTrue(snapshot.visibleRows().getFirst().primary());
        assertTrue(snapshot.visibleRows().getFirst().label().startsWith("Primary"));
        assertFalse(snapshot.visibleRows().get(7).primary());
    }

    @Test
    void leaveLobbyClearsPreMatchAssignmentAndHidesLobbyHud() {
        TargetingService targetingService = new TargetingService(
                null,
                new ConcurrentLinkedQueue<>(),
                new ConcurrentHashMap<>(),
                null,
                false
        );
        CtfMatchService matchService = new CtfMatchService(targetingService, null);

        CtfMatchService.JoinLobbyResult join = matchService.joinLobby("player-1", CtfMatchService.Team.RED);
        assertEquals(CtfMatchService.JoinStatus.JOINED, join.status());
        assertEquals(CtfMatchService.Team.RED, matchService.lobbyTeamFor("player-1"));
        assertTrue(matchService.lobbyHudStateFor("player-1").visible());
        assertEquals("Red", matchService.lobbyHudStateFor("player-1").teamName());

        assertTrue(matchService.leaveLobby("player-1"));
        assertEquals(null, matchService.lobbyTeamFor("player-1"));
        assertFalse(matchService.lobbyHudStateFor("player-1").visible());
        assertFalse(matchService.leaveLobby("player-1"));
    }

    @Test
    void startMatchSucceedsAndPromotesWaitingLobbyAssignments() throws Exception {
        TargetingService targetingService = new TargetingService(
                null,
                new ConcurrentLinkedQueue<>(),
                new ConcurrentHashMap<>(),
                null,
                false
        );
        CtfMatchService matchService = new CtfMatchService(targetingService, allocateTestCtfMode());
        matchService.joinLobby("player-1", CtfMatchService.Team.BLUE);

        CtfWorkflowFacade facade = new CtfWorkflowFacade(
                matchService,
                null,
                targetingService,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        CtfWorkflowFacade.ActionResult result = facade.startMatch(12);
        assertTrue(result.success());
        assertEquals(CtfWorkflowFacade.ResultCode.OK, result.code());
        assertTrue(result.message().contains("Started Capture The Flag match timer"));
        assertEquals(CtfMatchService.Team.BLUE, matchService.getActiveMatchTeams().get("player-1"));
        assertTrue(matchService.getLobbyWaitingTeamsSnapshot().isEmpty());
    }

    @Test
    void startMatchReportsMissingHomeStandsForActiveTeams() throws Exception {
        TargetingService targetingService = new TargetingService(
                null,
                new ConcurrentLinkedQueue<>(),
                new ConcurrentHashMap<>(),
                null,
                false
        );
        CtfMatchService matchService = new CtfMatchService(targetingService, allocateTestCtfMode());
        matchService.joinLobby("player-1", CtfMatchService.Team.RED);

        CtfStandRegistryRepository repository = new CtfStandRegistryRepository(tempDir, null);
        CtfFlagStateService flagStateService = new CtfFlagStateService(
                matchService,
                null,
                repository,
                (itemId, count) -> null,
                null
        );

        CtfWorkflowFacade facade = new CtfWorkflowFacade(
                matchService,
                null,
                targetingService,
                flagStateService,
                repository,
                null,
                null,
                null,
                null,
                null
        );

        CtfWorkflowFacade.ActionResult result = facade.startMatch(8);
        assertFalse(result.success());
        assertEquals(CtfWorkflowFacade.ResultCode.MISSING_HOME_STANDS, result.code());
        assertTrue(result.message().contains("Red"));
        assertTrue(result.detailMessage().contains("Stands page"));
    }

    private static CtfMode allocateTestCtfMode() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);

        CtfMode mode = (CtfMode) unsafe.allocateInstance(CtfMode.class);
        Field stateField = CtfMode.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(mode, new CtfState());
        return mode;
    }
}
