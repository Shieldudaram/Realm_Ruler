package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class CtfAdminPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_ADMIN;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_HOME = "Home";
    private static final String ACTION_NAV_REGION = "NavRegion";
    private static final String ACTION_NAV_STANDS = "NavStands";
    private static final String ACTION_NAV_BALLOONS = "NavBalloons";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;

    public CtfAdminPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Admin action failed because player state is unavailable.";
                statusIsError = true;
            } else if (!workflow.canAccessAdmin(context.player())) {
                statusMessage = "Admin tools are only available to staff with Capture The Flag setup permissions.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                switch (action) {
                    case ACTION_HOME -> {
                        openPage(context, ref, store, new CtfMainPage(context.playerRef(), workflow));
                        return;
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
                    case ACTION_NAV_BALLOONS -> {
                        if (!workflow.canManageRegion(context.player())) {
                            statusMessage = "Balloon tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                        } else {
                            openPage(context, ref, store, new CtfBalloonsPage(context.playerRef(), workflow));
                            return;
                        }
                    }
                    default -> {
                        statusMessage = "Invalid admin-page action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to handle admin-page action.");
            statusMessage = "Admin action failed. Try again.";
            statusIsError = true;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send admin-page UI update.");
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        CtfWorkflowFacade.AdminSnapshot snapshot = workflow.snapshotAdmin((context == null) ? null : context.player());
        setVisible(ui, "#RegionCard", snapshot.canManageRegion());
        setVisible(ui, "#RegionButton", snapshot.canManageRegion());
        setVisible(ui, "#RegionActionRow", snapshot.canManageRegion());
        setVisible(ui, "#StandsCard", snapshot.canManageStands());
        setVisible(ui, "#StandsButton", snapshot.canManageStands());
        setVisible(ui, "#StandsActionRow", snapshot.canManageStands());
        setVisible(ui, "#BalloonsCard", snapshot.canManageBalloons());
        setVisible(ui, "#BalloonsButton", snapshot.canManageBalloons());
        setVisible(ui, "#BalloonsActionRow", snapshot.canManageBalloons());

        setStatus(ui, "#StatusBanner", "#StatusLabel", statusMessage, statusIsError);

        if (events != null) {
            bind(events, "#HomeButton", ACTION_HOME);
            bind(events, "#RegionButton", ACTION_NAV_REGION);
            bind(events, "#BalloonsButton", ACTION_NAV_BALLOONS);
            bind(events, "#StandsButton", ACTION_NAV_STANDS);
        }
    }
}
