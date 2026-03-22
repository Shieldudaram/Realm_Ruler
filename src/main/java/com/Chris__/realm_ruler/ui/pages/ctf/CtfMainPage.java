package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class CtfMainPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_MAIN;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_NAV_PLAY = "NavPlay";
    private static final String ACTION_NAV_SHOP = "NavShop";
    private static final String ACTION_NAV_MATCH = "NavMatch";
    private static final String ACTION_NAV_ADMIN = "NavAdmin";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;

    public CtfMainPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Capture The Flag menu action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                switch (action) {
                    case ACTION_NAV_PLAY -> {
                        openPage(context, ref, store, new CtfPlayPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_NAV_SHOP -> {
                        openPage(context, ref, store, new CtfShopPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_NAV_MATCH -> {
                        openPage(context, ref, store, new CtfMatchPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_NAV_ADMIN -> {
                        if (!workflow.canAccessAdmin(context.player())) {
                            statusMessage = "Admin tools are only available to staff with Capture The Flag setup permissions.";
                            statusIsError = true;
                        } else {
                            openPage(context, ref, store, new CtfAdminPage(context.playerRef(), workflow));
                            return;
                        }
                    }
                    default -> {
                        statusMessage = "Invalid Capture The Flag menu action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to navigate from the main CTF UI.");
            if (context != null) {
                sendFallbackMessage(context.player(), "Failed to open the requested Capture The Flag page. Try again.");
            }
            statusMessage = "Failed to open the requested Capture The Flag page. Try again.";
            statusIsError = true;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send main CTF UI update.");
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        String uuid = (context == null) ? null : context.uuid();
        CtfWorkflowFacade.HubSnapshot snapshot = workflow.snapshotHub((context == null) ? null : context.player(), uuid);
        setLabel(ui, "#PointsLabel", pointsLabel(snapshot.points()));
        setLabel(ui, "#CurrentTeamValue", snapshot.currentTeam());
        setLabel(ui, "#MatchStateValue", snapshot.matchRunning() ? "Match Running" : "Lobby Open");
        setLabel(ui, "#TimerValue", snapshot.matchRunning()
                ? CtfWorkflowFacade.formatSeconds(snapshot.remainingSeconds())
                : "--:--");
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

        setStatus(ui, "#StatusBanner", "#StatusLabel", statusMessage, statusIsError);
        setVisible(ui, "#AdminButton", snapshot.canAccessAdmin());
        setVisible(ui, "#AdminActionRow", snapshot.canAccessAdmin());
        setVisible(ui, "#AdminCard", snapshot.canAccessAdmin());

        if (events != null) {
            bind(events, "#PlayButton", ACTION_NAV_PLAY);
            bind(events, "#ShopButton", ACTION_NAV_SHOP);
            bind(events, "#MatchButton", ACTION_NAV_MATCH);
            bind(events, "#AdminButton", ACTION_NAV_ADMIN);
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
