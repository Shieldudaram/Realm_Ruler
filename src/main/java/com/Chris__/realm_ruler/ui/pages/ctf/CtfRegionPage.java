package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class CtfRegionPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_REGION;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_BACK = "Back";
    private static final String ACTION_CREATE = "Create";
    private static final String ACTION_POS1 = "Pos1";
    private static final String ACTION_POS2 = "Pos2";
    private static final String ACTION_CLEAR = "Clear";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;
    private volatile boolean clearArmed = false;

    public CtfRegionPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Region action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                boolean canManage = workflow.canManageRegion(context.player());
                switch (action) {
                    case ACTION_BACK -> {
                        openPage(context, ref, store, new CtfAdminPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_CREATE -> {
                        if (!canManage) {
                            statusMessage = "Region tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                            break;
                        }
                        clearArmed = false;
                        applyResult(workflow.createRegionHere(canManage, context.uuid()));
                    }
                    case ACTION_POS1 -> {
                        if (!canManage) {
                            statusMessage = "Region tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                            break;
                        }
                        clearArmed = false;
                        applyResult(workflow.setRegionPosHere(canManage, context.uuid(), true));
                    }
                    case ACTION_POS2 -> {
                        if (!canManage) {
                            statusMessage = "Region tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                            break;
                        }
                        clearArmed = false;
                        applyResult(workflow.setRegionPosHere(canManage, context.uuid(), false));
                    }
                    case ACTION_CLEAR -> {
                        if (!canManage) {
                            statusMessage = "Region tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                            clearArmed = false;
                            break;
                        }
                        if (!clearArmed) {
                            clearArmed = true;
                            statusMessage = "Press Clear Region again to confirm.";
                            statusIsError = true;
                        } else {
                            clearArmed = false;
                            applyResult(workflow.clearRegion(canManage));
                        }
                    }
                    default -> {
                        statusMessage = "Invalid region-page action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to handle region-page action.");
            statusMessage = "Region action failed. Try again.";
            statusIsError = true;
            clearArmed = false;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send region-page UI update.");
    }

    private void applyResult(CtfWorkflowFacade.ActionResult result) {
        statusMessage = (result == null) ? "Action failed." : result.combinedMessage();
        statusIsError = result == null || !result.success();
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        CtfWorkflowFacade.RegionSnapshot snapshot = workflow.snapshotRegion(context != null && workflow.canManageRegion(context.player()));
        setLabel(ui, "#WorldValue", snapshot.worldName());
        setLabel(ui, "#EnabledValue", String.valueOf(snapshot.enabled()));
        setLabel(ui, "#ReadyValue", String.valueOf(snapshot.ready()));
        setLabel(ui, "#Pos1Value", snapshot.pos1());
        setLabel(ui, "#Pos2Value", snapshot.pos2());
        setLabel(ui, "#ClearButton", clearArmed ? "Confirm Clear" : "Clear Region");
        setVisible(ui, "#CreateButton", snapshot.canManage());
        setVisible(ui, "#CreateAction", snapshot.canManage());
        setVisible(ui, "#Pos1Button", snapshot.canManage());
        setVisible(ui, "#Pos1Action", snapshot.canManage());
        setVisible(ui, "#Pos2Button", snapshot.canManage());
        setVisible(ui, "#Pos2Action", snapshot.canManage());
        setVisible(ui, "#ClearButton", snapshot.canManage());
        setVisible(ui, "#ClearAction", snapshot.canManage());

        setStatus(ui, "#StatusBanner", "#StatusLabel", statusMessage, statusIsError);

        if (events != null) {
            bind(events, "#BackButton", ACTION_BACK);
            bind(events, "#CreateButton", ACTION_CREATE);
            bind(events, "#Pos1Button", ACTION_POS1);
            bind(events, "#Pos2Button", ACTION_POS2);
            bind(events, "#ClearButton", ACTION_CLEAR);
        }
    }
}
