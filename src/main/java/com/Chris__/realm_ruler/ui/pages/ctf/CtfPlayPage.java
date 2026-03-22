package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class CtfPlayPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_PLAY;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_HOME = "Home";
    private static final String ACTION_SHOP = "Shop";
    private static final String ACTION_JOIN = "Join";
    private static final String ACTION_LEAVE = "Leave";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;
    private volatile boolean emphasizeJoinWarning = false;
    private volatile CtfWorkflowFacade.ResultCode emphasizedJoinWarningCode = CtfWorkflowFacade.ResultCode.OK;

    public CtfPlayPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
        super(playerRef, workflow, LOGGER);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                      @Nonnull com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(UI_PATH);
        render(ui, events, resolvePlayerContext(ref, store));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, CtfPageEventData data) {
        PlayerContext context = resolvePlayerContext(ref, store);
        try {
            if (context == null) {
                statusMessage = "Play action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                switch (action) {
                    case ACTION_HOME -> {
                        openPage(context, ref, store, new CtfMainPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_SHOP -> {
                        openPage(context, ref, store, new CtfShopPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_JOIN -> applyResult(workflow.joinLobby(
                            context.player(),
                            context.uuid(),
                            parseRequestedTeam((data == null) ? "" : data.getValue())
                    ));
                    case ACTION_LEAVE -> applyResult(workflow.leaveLobby(context.player(), context.uuid()));
                    default -> {
                        statusMessage = "Invalid play-page action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to handle play-page action.");
            statusMessage = "Play action failed. Try again.";
            statusIsError = true;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send play-page UI update.");
    }

    private void applyResult(CtfWorkflowFacade.ActionResult result) {
        if (result == null) {
            emphasizeJoinWarning = false;
            emphasizedJoinWarningCode = CtfWorkflowFacade.ResultCode.OK;
            statusMessage = "Action failed.";
            statusIsError = true;
            return;
        }

        if (ownsJoinWarning(result.code())) {
            emphasizeJoinWarning = !result.success();
            emphasizedJoinWarningCode = result.code();
            statusMessage = "";
            statusIsError = false;
            return;
        }

        emphasizeJoinWarning = false;
        emphasizedJoinWarningCode = CtfWorkflowFacade.ResultCode.OK;
        statusMessage = result.combinedMessage();
        statusIsError = !result.success();
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        String uuid = (context == null) ? null : context.uuid();
        CtfWorkflowFacade.PlaySnapshot snapshot = workflow.snapshotPlay((context == null) ? null : context.player(), uuid);

        setLabel(ui, "#PointsLabel", pointsLabel(snapshot.points()));
        setLabel(ui, "#CurrentTeamValue", snapshot.currentTeam());
        setLabel(ui, "#MatchStateValue", snapshot.matchRunning() ? "Match Running" : "Lobby Open");
        setLabel(ui, "#WaitingValue", String.valueOf(snapshot.waitingCount()));
        setLabel(ui, "#ActiveTeamCountValue", String.valueOf(countActiveTeams(snapshot.activeTeamCounts())));
        renderTeamCounts(
                ui,
                snapshot.lobbyTeamCounts(),
                "#LobbyRedValue",
                "#LobbyBlueValue",
                "#LobbyYellowValue",
                "#LobbyWhiteValue"
        );
        renderTeamCounts(
                ui,
                snapshot.activeTeamCounts(),
                "#ActiveRedValue",
                "#ActiveBlueValue",
                "#ActiveYellowValue",
                "#ActiveWhiteValue"
        );
        setVisible(ui, "#LeaveButton", snapshot.canLeaveLobby());
        setVisible(ui, "#LeaveAction", snapshot.canLeaveLobby());

        boolean joinBlocked = !snapshot.canJoinLobby() && !safe(snapshot.joinBlockedReason()).isBlank();
        boolean joinWarningOwned = ownsJoinWarning(snapshot.joinBlockedCode());
        if (!joinBlocked || !joinWarningOwned || snapshot.joinBlockedCode() != emphasizedJoinWarningCode) {
            emphasizeJoinWarning = false;
            emphasizedJoinWarningCode = CtfWorkflowFacade.ResultCode.OK;
        }
        boolean showEmphasizedJoinWarning = joinBlocked && joinWarningOwned && emphasizeJoinWarning;
        boolean showPassiveJoinWarning = joinBlocked && !showEmphasizedJoinWarning;

        setVisible(ui, "#JoinWarningCard", showPassiveJoinWarning);
        setVisible(ui, "#JoinWarningLabel", showPassiveJoinWarning);
        setVisible(ui, "#JoinWarningActiveCard", showEmphasizedJoinWarning);
        setVisible(ui, "#JoinWarningActiveLabel", showEmphasizedJoinWarning);
        setLabel(ui, "#JoinWarningLabel", snapshot.joinBlockedReason());
        setLabel(ui, "#JoinWarningActiveLabel", snapshot.joinBlockedReason());

        setStatus(ui, "#StatusBanner", "#StatusLabel", statusMessage, statusIsError);

        if (events != null) {
            bind(events, "#HomeButton", ACTION_HOME);
            bind(events, "#ShopButton", ACTION_SHOP);
            bind(events, "#JoinRandomButton", ACTION_JOIN, "random");
            bind(events, "#JoinRedButton", ACTION_JOIN, "red");
            bind(events, "#JoinBlueButton", ACTION_JOIN, "blue");
            bind(events, "#JoinYellowButton", ACTION_JOIN, "yellow");
            bind(events, "#JoinWhiteButton", ACTION_JOIN, "white");
            bind(events, "#LeaveButton", ACTION_LEAVE);
        }
    }

    private static CtfMatchService.Team parseRequestedTeam(String value) {
        String normalized = safe(value).toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank() || "random".equals(normalized)) {
            return null;
        }
        return CtfMatchService.parseTeam(normalized);
    }

    private static boolean ownsJoinWarning(CtfWorkflowFacade.ResultCode code) {
        return code == CtfWorkflowFacade.ResultCode.ARMOR_BLOCKED
                || code == CtfWorkflowFacade.ResultCode.OBJECTIVE_FLAG_BLOCKED;
    }

    private static void renderTeamCounts(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                                         CtfWorkflowFacade.TeamCountSummary counts,
                                         String redSelector,
                                         String blueSelector,
                                         String yellowSelector,
                                         String whiteSelector) {
        CtfWorkflowFacade.TeamCountSummary safeCounts = (counts == null)
                ? new CtfWorkflowFacade.TeamCountSummary(0, 0, 0, 0)
                : counts;
        setLabel(ui, redSelector, "Red " + safeCounts.red());
        setLabel(ui, blueSelector, "Blue " + safeCounts.blue());
        setLabel(ui, yellowSelector, "Yellow " + safeCounts.yellow());
        setLabel(ui, whiteSelector, "White " + safeCounts.white());
    }

    private static int countActiveTeams(CtfWorkflowFacade.TeamCountSummary counts) {
        if (counts == null) return 0;
        int total = 0;
        if (counts.red() > 0) total++;
        if (counts.blue() > 0) total++;
        if (counts.yellow() > 0) total++;
        if (counts.white() > 0) total++;
        return total;
    }
}
