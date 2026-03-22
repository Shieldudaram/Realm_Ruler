package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class CtfMatchPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_MATCH;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_HOME = "Home";
    private static final String ACTION_MINUTES = "Minutes";
    private static final String ACTION_START = "Start";
    private static final String ACTION_STOP = "Stop";
    private static final String ACTION_NAV_REGION = "NavRegion";
    private static final String ACTION_NAV_STANDS = "NavStands";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;
    private volatile int selectedMinutes = CtfWorkflowFacade.DEFAULT_MATCH_MINUTES;
    private volatile CtfWorkflowFacade.ResultCode lastResultCode = CtfWorkflowFacade.ResultCode.OK;
    private volatile boolean pendingStart = false;
    private volatile int pendingStartSeconds = CtfWorkflowFacade.DEFAULT_MATCH_MINUTES * 60;

    public CtfMatchPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Match action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                String value = safe((data == null) ? "" : data.getValue());
                switch (action) {
                    case ACTION_HOME -> {
                        openPage(context, ref, store, new CtfMainPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_MINUTES -> {
                        int delta = "-".equals(value) ? -1 : 1;
                        selectedMinutes = CtfWorkflowFacade.clampMatchMinutes(selectedMinutes + delta);
                        lastResultCode = CtfWorkflowFacade.ResultCode.OK;
                        pendingStart = false;
                        statusMessage = "";
                        statusIsError = false;
                        LOGGER.atInfo().log("[RR-CTF] Match page adjusted minutes to %s", selectedMinutes);
                    }
                    case ACTION_START -> {
                        LOGGER.atInfo().log("[RR-CTF] Match page received Start action (minutes=%s)", selectedMinutes);
                        applyResult(workflow.startMatch(selectedMinutes), selectedMinutes);
                    }
                    case ACTION_STOP -> {
                        LOGGER.atInfo().log("[RR-CTF] Match page received Stop action.");
                        pendingStart = false;
                        applyResult(workflow.stopMatch(), selectedMinutes);
                    }
                    case ACTION_NAV_REGION -> {
                        if (!workflow.canManageRegion(context.player())) {
                            statusMessage = "Region tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                        } else {
                            openPage(context, ref, store, new CtfRegionPage(context.playerRef(), workflow));
                            return;
                        }
                    }
                    case ACTION_NAV_STANDS -> {
                        if (!workflow.canManageStands(context.player())) {
                            statusMessage = "Stand tools require " + CtfWorkflowFacade.STAND_PERMISSION + ".";
                            statusIsError = true;
                        } else {
                            openPage(context, ref, store, new CtfStandsPage(context.playerRef(), workflow));
                            return;
                        }
                    }
                    default -> {
                        statusMessage = "Invalid match-page action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to handle match-page action.");
            statusMessage = "Match action failed. Try again.";
            statusIsError = true;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send match-page UI update.");
    }

    private void applyResult(CtfWorkflowFacade.ActionResult result, int requestedMinutes) {
        statusMessage = (result == null) ? "Action failed." : result.combinedMessage();
        statusIsError = result == null || !result.success();
        lastResultCode = (result == null) ? CtfWorkflowFacade.ResultCode.INVALID_INPUT : result.code();
        pendingStart = result != null
                && result.success()
                && result.code() == CtfWorkflowFacade.ResultCode.OK
                && safe(result.message()).startsWith("Started Capture The Flag match timer");
        pendingStartSeconds = Math.max(0, requestedMinutes) * 60;
        if (result == null) {
            LOGGER.atWarning().log("[RR-CTF] Match page received a null workflow result.");
            return;
        }
        if (result.success()) {
            LOGGER.atInfo().log("[RR-CTF] Match workflow result success code=%s message=%s", result.code(), safe(result.message()));
        } else {
            LOGGER.atWarning().log("[RR-CTF] Match workflow result failure code=%s message=%s detail=%s",
                    result.code(),
                    safe(result.message()),
                    safe(result.detailMessage()));
        }
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        String uuid = (context == null) ? null : context.uuid();
        CtfWorkflowFacade.MatchSnapshot snapshot = workflow.snapshotMatch((context == null) ? null : context.player(), uuid);
        if (snapshot.matchRunning()) {
            pendingStart = false;
        }

        boolean effectiveMatchRunning = snapshot.matchRunning() || pendingStart;
        setLabel(ui, "#PointsLabel", pointsLabel(snapshot.points()));
        setLabel(ui, "#CurrentTeamValue", snapshot.currentTeam());
        setLabel(ui, "#MatchStateValue", snapshot.matchRunning()
                ? "Match Running"
                : (pendingStart ? "Starting Match" : "Lobby Open"));
        setLabel(ui, "#TimerValue", snapshot.matchRunning()
                ? CtfWorkflowFacade.formatSeconds(snapshot.remainingSeconds())
                : (pendingStart ? CtfWorkflowFacade.formatSeconds(pendingStartSeconds) : "--:--"));
        setLabel(ui, "#WaitingValue", String.valueOf(snapshot.waitingCount()));
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
        setLabel(ui, "#MinutesValue", String.valueOf(selectedMinutes));

        boolean showRegionShortcut = false;
        boolean showStandsShortcut = lastResultCode == CtfWorkflowFacade.ResultCode.MISSING_HOME_STANDS && snapshot.canManageStands();
        setVisible(ui, "#StartButton", !effectiveMatchRunning);
        setVisible(ui, "#StartAction", !effectiveMatchRunning);
        setVisible(ui, "#StopButton", effectiveMatchRunning);
        setVisible(ui, "#StopAction", effectiveMatchRunning);
        setVisible(ui, "#RegionShortcutButton", showRegionShortcut);
        setVisible(ui, "#RegionShortcutAction", showRegionShortcut);
        setVisible(ui, "#RegionShortcutCard", showRegionShortcut);
        setVisible(ui, "#StandsShortcutButton", showStandsShortcut);
        setVisible(ui, "#StandsShortcutAction", showStandsShortcut);
        setVisible(ui, "#StandsShortcutCard", showStandsShortcut);

        setStatus(ui, "#StatusBanner", "#StatusLabel", statusMessage, statusIsError);

        if (events != null) {
            bind(events, "#HomeButton", ACTION_HOME);
            bind(events, "#MinutesMinusButton", ACTION_MINUTES, "-");
            bind(events, "#MinutesPlusButton", ACTION_MINUTES, "+");
            bind(events, "#StartButton", ACTION_START);
            bind(events, "#StopButton", ACTION_STOP);
            bind(events, "#RegionShortcutButton", ACTION_NAV_REGION);
            bind(events, "#StandsShortcutButton", ACTION_NAV_STANDS);
        }
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
}
