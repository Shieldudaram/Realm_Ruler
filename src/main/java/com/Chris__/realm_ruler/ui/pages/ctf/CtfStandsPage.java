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

public final class CtfStandsPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_STANDS;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int MAX_VISIBLE_ROWS = 8;
    private static final String ACTION_BACK = "Back";
    private static final String ACTION_TEAM = "Team";
    private static final String ACTION_SELECT = "Select";
    private static final String ACTION_ADD = "Add";
    private static final String ACTION_REMOVE = "Remove";
    private static final String ACTION_PRIMARY = "Primary";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;
    private volatile CtfMatchService.Team selectedTeam = CtfMatchService.Team.RED;
    private volatile int selectedRowIndex = -1;
    private volatile boolean removeArmed = false;

    public CtfStandsPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Stand action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                String value = safe((data == null) ? "" : data.getValue());
                boolean canManage = workflow.canManageStands(context.player());
                switch (action) {
                    case ACTION_BACK -> {
                        openPage(context, ref, store, new CtfAdminPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_TEAM -> {
                        if (!canManage) {
                            statusMessage = "Stand tools require " + CtfWorkflowFacade.STAND_PERMISSION + ".";
                            statusIsError = true;
                            break;
                        }
                        CtfMatchService.Team nextTeam = CtfMatchService.parseTeam(value);
                        if (nextTeam != null) {
                            selectedTeam = nextTeam;
                            selectedRowIndex = -1;
                            removeArmed = false;
                            statusMessage = "";
                            statusIsError = false;
                        } else {
                            statusMessage = "Invalid team selection.";
                            statusIsError = true;
                        }
                    }
                    case ACTION_SELECT -> {
                        if (!canManage) {
                            statusMessage = "Stand tools require " + CtfWorkflowFacade.STAND_PERMISSION + ".";
                            statusIsError = true;
                            break;
                        }
                        CtfWorkflowFacade.StandsSnapshot snapshot = workflow.snapshotStands(true, selectedTeam, MAX_VISIBLE_ROWS);
                        int requestedIndex = parseRowIndex(value);
                        if (requestedIndex < 0 || requestedIndex >= snapshot.visibleRows().size()) {
                            statusMessage = "Selected stand row is no longer available.";
                            statusIsError = true;
                            break;
                        }
                        selectedRowIndex = requestedIndex;
                        removeArmed = false;
                        statusMessage = "Selected stand row " + (selectedRowIndex + 1) + ".";
                        statusIsError = false;
                    }
                    case ACTION_ADD -> {
                        if (!canManage) {
                            statusMessage = "Stand tools require " + CtfWorkflowFacade.STAND_PERMISSION + ".";
                            statusIsError = true;
                            break;
                        }
                        removeArmed = false;
                        applyResult(workflow.addStandHere(true, context.uuid(), selectedTeam));
                    }
                    case ACTION_REMOVE -> {
                        if (!canManage) {
                            statusMessage = "Stand tools require " + CtfWorkflowFacade.STAND_PERMISSION + ".";
                            statusIsError = true;
                        } else {
                            handleRemove(context);
                        }
                    }
                    case ACTION_PRIMARY -> {
                        if (!canManage) {
                            statusMessage = "Stand tools require " + CtfWorkflowFacade.STAND_PERMISSION + ".";
                            statusIsError = true;
                        } else {
                            handlePrimary(context);
                        }
                    }
                    default -> {
                        statusMessage = "Invalid stands-page action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to handle stands-page action.");
            statusMessage = "Stand action failed. Try again.";
            statusIsError = true;
            removeArmed = false;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send stands-page UI update.");
    }

    private void handleRemove(PlayerContext context) {
        CtfWorkflowFacade.StandsSnapshot snapshot = workflow.snapshotStands(workflow.canManageStands(context.player()), selectedTeam, MAX_VISIBLE_ROWS);
        CtfWorkflowFacade.StandRow selectedRow = resolveSelectedRow(snapshot);
        if (selectedRow == null) {
            statusMessage = "Select a stand row first.";
            statusIsError = true;
            removeArmed = false;
            return;
        }
        if (!removeArmed) {
            removeArmed = true;
            statusMessage = "Press Remove Selected again to confirm.";
            statusIsError = true;
            return;
        }

        removeArmed = false;
        CtfWorkflowFacade.ActionResult result = workflow.removeStand(
                workflow.canManageStands(context.player()),
                selectedTeam,
                selectedRow.worldName(),
                selectedRow.x(),
                selectedRow.y(),
                selectedRow.z()
        );
        if (result.success()) {
            selectedRowIndex = -1;
        }
        applyResult(result);
    }

    private void handlePrimary(PlayerContext context) {
        removeArmed = false;
        CtfWorkflowFacade.StandsSnapshot snapshot = workflow.snapshotStands(workflow.canManageStands(context.player()), selectedTeam, MAX_VISIBLE_ROWS);
        CtfWorkflowFacade.StandRow selectedRow = resolveSelectedRow(snapshot);
        if (selectedRow == null) {
            statusMessage = "Select a stand row first.";
            statusIsError = true;
            return;
        }
        applyResult(workflow.setPrimaryStand(
                workflow.canManageStands(context.player()),
                selectedTeam,
                selectedRow.worldName(),
                selectedRow.x(),
                selectedRow.y(),
                selectedRow.z()
        ));
    }

    private void applyResult(CtfWorkflowFacade.ActionResult result) {
        statusMessage = (result == null) ? "Action failed." : result.combinedMessage();
        statusIsError = result == null || !result.success();
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        CtfWorkflowFacade.StandsSnapshot snapshot = workflow.snapshotStands(
                context != null && workflow.canManageStands(context.player()),
                selectedTeam,
                MAX_VISIBLE_ROWS
        );
        if (selectedRowIndex >= snapshot.visibleRows().size()) {
            selectedRowIndex = -1;
            removeArmed = false;
        }

        CtfWorkflowFacade.StandRow selectedRow = resolveSelectedRow(snapshot);
        setLabel(ui, "#SelectionValue", (selectedRow == null) ? "No stand selected" : "Selected: " + selectedRow.label());
        setVisible(ui, "#AddButton", snapshot.canManage());
        setVisible(ui, "#AddAction", snapshot.canManage());
        setVisible(ui, "#RemoveButton", snapshot.canManage() && selectedRow != null);
        setVisible(ui, "#RemoveAction", snapshot.canManage() && selectedRow != null);
        setVisible(ui, "#PrimaryButton", snapshot.canManage() && selectedRow != null);
        setVisible(ui, "#PrimaryAction", snapshot.canManage() && selectedRow != null);
        setLabel(ui, "#RemoveButton", removeArmed ? "Confirm Remove" : "Remove Selected");
        setVisible(ui, "#TeamRedSelectedAccent", snapshot.selectedTeam() == CtfMatchService.Team.RED);
        setVisible(ui, "#TeamBlueSelectedAccent", snapshot.selectedTeam() == CtfMatchService.Team.BLUE);
        setVisible(ui, "#TeamYellowSelectedAccent", snapshot.selectedTeam() == CtfMatchService.Team.YELLOW);
        setVisible(ui, "#TeamWhiteSelectedAccent", snapshot.selectedTeam() == CtfMatchService.Team.WHITE);

        String note = snapshot.truncated() ? "Showing first 8 stands." : "";
        setStatus(ui, "#StatusBanner", "#StatusLabel", joinStatus(statusMessage, note), statusIsError);

        for (int index = 0; index < MAX_VISIBLE_ROWS; index++) {
            String rowGroup = "#Row" + index;
            String rowLabel = "#Row" + index + "Label";
            String rowAction = "#Row" + index + "Action";
            String rowButton = "#Row" + index + "Button";
            String rowSelectedAccent = "#Row" + index + "SelectedAccent";
            if (events != null) {
                bind(events, rowButton, ACTION_SELECT, String.valueOf(index));
            }
            if (index >= snapshot.visibleRows().size()) {
                setVisible(ui, rowGroup, true);
                setVisible(ui, rowSelectedAccent, false);
                setLabel(ui, rowLabel, "No stand registered");
                setVisible(ui, rowButton, false);
                setVisible(ui, rowAction, false);
                continue;
            }

            CtfWorkflowFacade.StandRow row = snapshot.visibleRows().get(index);
            boolean selected = index == selectedRowIndex;
            setVisible(ui, rowGroup, true);
            setVisible(ui, rowSelectedAccent, selected);
            setLabel(ui, rowLabel, row.label());
            setLabel(ui, rowButton, selected ? "Selected" : "Select");
            setVisible(ui, rowButton, snapshot.canManage());
            setVisible(ui, rowAction, snapshot.canManage());
        }

        if (events != null) {
            bind(events, "#BackButton", ACTION_BACK);
            bind(events, "#TeamRedButton", ACTION_TEAM, "red");
            bind(events, "#TeamBlueButton", ACTION_TEAM, "blue");
            bind(events, "#TeamYellowButton", ACTION_TEAM, "yellow");
            bind(events, "#TeamWhiteButton", ACTION_TEAM, "white");
            bind(events, "#AddButton", ACTION_ADD);
            bind(events, "#RemoveButton", ACTION_REMOVE);
            bind(events, "#PrimaryButton", ACTION_PRIMARY);
        }
    }

    private CtfWorkflowFacade.StandRow resolveSelectedRow(CtfWorkflowFacade.StandsSnapshot snapshot) {
        if (snapshot == null || selectedRowIndex < 0 || selectedRowIndex >= snapshot.visibleRows().size()) {
            return null;
        }
        return snapshot.visibleRows().get(selectedRowIndex);
    }

    private static int parseRowIndex(String value) {
        try {
            return Math.max(-1, Integer.parseInt(safe(value)));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String joinStatus(String left, String right) {
        String safeLeft = safe(left);
        String safeRight = safe(right);
        if (safeLeft.isBlank()) return safeRight;
        if (safeRight.isBlank()) return safeLeft;
        return safeLeft + " " + safeRight;
    }
}
