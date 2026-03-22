package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.match.CtfBalloonSpawnService;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class CtfBalloonsPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_BALLOONS;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_BACK = "Back";
    private static final String ACTION_COUNT = "Count";
    private static final String ACTION_SPAWN = "Spawn";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;
    private volatile int spawnCount = CtfWorkflowFacade.DEFAULT_BALLOON_COUNT;

    public CtfBalloonsPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Balloon action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                boolean canManage = workflow.canManageRegion(context.player());
                switch (action) {
                    case ACTION_BACK -> {
                        openPage(context, ref, store, new CtfAdminPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_COUNT -> {
                        int delta = ("-".equals(safe((data == null) ? "" : data.getValue()))) ? -1 : 1;
                        spawnCount = CtfWorkflowFacade.clampBalloonCount(spawnCount + delta);
                        statusMessage = "";
                        statusIsError = false;
                    }
                    case ACTION_SPAWN -> {
                        if (!canManage) {
                            statusMessage = "Balloon tools require " + CtfWorkflowFacade.REGION_PERMISSION + ".";
                            statusIsError = true;
                        } else {
                            applyResult(workflow.spawnBalloons(canManage, context.uuid(), spawnCount));
                        }
                    }
                    default -> {
                        statusMessage = "Invalid balloons-page action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Failed to handle balloons-page action.");
            statusMessage = "Balloon action failed. Try again.";
            statusIsError = true;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send balloons-page UI update.");
    }

    private void applyResult(CtfWorkflowFacade.ActionResult result) {
        statusMessage = (result == null) ? "Action failed." : result.combinedMessage();
        statusIsError = result == null || !result.success();
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        CtfWorkflowFacade.BalloonsSnapshot snapshot = workflow.snapshotBalloons(
                context != null && workflow.canManageRegion(context.player())
        );
        CtfBalloonSpawnService.StatusSnapshot status = snapshot.status();

        setLabel(ui, "#SpawnCountValue", String.valueOf(spawnCount));
        setLabel(ui, "#MatchRunningValue", boolValue(status == null ? null : status.matchRunning()));
        setLabel(ui, "#RegionConfiguredValue", boolValue(status == null ? null : status.regionConfigured()));
        setLabel(ui, "#RegionReadyValue", boolValue(status == null ? null : status.regionReady()));
        setLabel(ui, "#RegionWorldValue", (status == null) ? "<unset>" : safe(status.regionWorldName(), "<unset>"));
        setLabel(ui, "#WorldResolvedValue", boolValue(status == null ? null : status.regionWorldResolved()));
        setLabel(ui, "#ActiveValue", (status == null) ? "0/0" : status.activeCount() + "/" + status.maxActive());
        setLabel(ui, "#NextSpawnValue", (status == null || status.secondsUntilNextSpawn() < 0) ? "<none>" : status.secondsUntilNextSpawn() + "s");
        setLabel(ui, "#RoleReadyValue", boolValue(status == null ? null : status.balloonRoleResolvable()));
        setLabel(ui, "#DirectApiValue", boolValue(status == null ? null : status.directApiReady()));
        setLabel(ui, "#FallbackReadyValue", boolValue(status == null ? null : status.fallbackReady()));
        setLabel(ui, "#FallbackTemplateValue", (status == null) ? "<unset>" : safe(status.selectedFallbackTemplate(), "<unresolved>"));
        setLabel(ui, "#FallbackCooldownValue", (status == null) ? "0s" : status.fallbackCooldownRemainingSeconds() + "s");
        setLabel(ui, "#LastErrorValue", (status == null) ? "<none>" : safe(status.lastFallbackError(), "<none>"));
        setLabel(ui, "#BlockingReasonValue", (status == null) ? "<none>" : safe(status.blockingReason(), "<none>"));
        setVisible(ui, "#SpawnButton", snapshot.canManage());
        setVisible(ui, "#SpawnNowAction", snapshot.canManage());

        setStatus(ui, "#StatusBanner", "#StatusLabel", statusMessage, statusIsError);

        if (events != null) {
            bind(events, "#BackButton", ACTION_BACK);
            bind(events, "#CountMinusButton", ACTION_COUNT, "-");
            bind(events, "#CountPlusButton", ACTION_COUNT, "+");
            bind(events, "#SpawnButton", ACTION_SPAWN);
        }
    }

    private static String boolValue(Boolean value) {
        return (value == null) ? "<unknown>" : String.valueOf(value);
    }

    private static String safe(String text, String fallback) {
        String safeText = safe(text);
        return safeText.isBlank() ? fallback : safeText;
    }
}
