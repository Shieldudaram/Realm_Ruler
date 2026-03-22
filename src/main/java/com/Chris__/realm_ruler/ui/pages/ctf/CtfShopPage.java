package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.match.CtfShopService;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public final class CtfShopPage extends AbstractCtfPage {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_SHOP;
    private static final int MAX_ENTRIES = 16;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String ACTION_HOME = "Home";
    private static final String ACTION_VIEW = "View";
    private static final String ACTION_BUY = "Buy";
    private static final String SLOT_PREFIX = "slot:";

    private volatile String statusMessage = "";
    private volatile boolean statusIsError = false;
    private volatile String selectedItemId = "";

    public CtfShopPage(@Nonnull PlayerRef playerRef, CtfWorkflowFacade workflow) {
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
                statusMessage = "Shop action failed because player state is unavailable.";
                statusIsError = true;
            } else {
                String action = safe((data == null) ? "" : data.getAction());
                String value = safe((data == null) ? "" : data.getValue());
                switch (action) {
                    case ACTION_HOME -> {
                        openPage(context, ref, store, new CtfMainPage(context.playerRef(), workflow));
                        return;
                    }
                    case ACTION_VIEW -> handleViewAction(context, value);
                    case ACTION_BUY -> {
                        CtfShopService.ShopItemView item = resolveRequestedItem(context, value);
                        if (item == null || safe(item.id()).isBlank()) {
                            statusMessage = "That shop item is no longer available.";
                            statusIsError = true;
                            break;
                        }
                        selectedItemId = safe(item.id());
                        CtfWorkflowFacade.ActionResult result = workflow.purchaseShopItem(context.player(), context.uuid(), selectedItemId);
                        statusMessage = (result == null) ? "Purchase failed." : result.combinedMessage();
                        statusIsError = result == null || !result.success();
                        if (result != null) {
                            sendFallbackMessage(context.player(), result.message());
                            if (result.detailMessage() != null && !result.detailMessage().isBlank()) {
                                sendFallbackMessage(context.player(), result.detailMessage());
                            }
                        }
                    }
                    default -> {
                        statusMessage = "Invalid shop action.";
                        statusIsError = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[RR-CTF] Shop UI action failed.");
            if (context != null) {
                sendFallbackMessage(context.player(), "Shop action failed. Try again.");
            }
            statusMessage = "Shop action failed. Try again.";
            statusIsError = true;
        }

        sendSafeUpdate(update -> render(update, null, context), "[RR-CTF] Failed to send shop UI update.");
    }

    private void render(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                        com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                        PlayerContext context) {
        String uuid = (context == null) ? null : context.uuid();
        CtfWorkflowFacade.ShopSnapshot snapshot = workflow.snapshotShop(uuid, selectedItemId, MAX_ENTRIES);
        CtfShopService.ShopItemView selectedItem = snapshot.selectedItem();
        if (selectedItem != null && !selectedItem.id().equalsIgnoreCase(selectedItemId)) {
            selectedItemId = selectedItem.id();
        }

        setLabel(ui, "#PointsLabel", pointsLabel(snapshot.points()));

        String note = snapshot.truncated() ? "Showing first 16 items." : "";
        String combinedStatus = combineStatus(statusMessage, note);
        setStatus(ui, "#StatusBanner", "#StatusLabel", combinedStatus, statusIsError);

        renderSelectedDetails(ui, selectedItem);
        renderEntries(ui, events, snapshot.visibleItems());

        if (events != null) {
            bind(events, "#HomeButton", ACTION_HOME);
        }
    }

    private void renderSelectedDetails(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                                       CtfShopService.ShopItemView selectedItem) {
        if (selectedItem == null) {
            setLabel(ui, "#DetailNameValue", "No item selected");
            setLabel(ui, "#DetailIdValue", "<none>");
            setLabel(ui, "#DetailCostValue", "0");
            setLabel(ui, "#DetailAvailabilityValue", "<none>");
            setLabel(ui, "#DetailTeamRuleValue", "<none>");
            setLabel(ui, "#DetailRewardsValue", "<none>");
            return;
        }

        String teamRule = safe(selectedItem.teamRule(), "any");
        String team = safe(selectedItem.team(), "any");
        String teamRuleText = "any".equalsIgnoreCase(team) ? teamRule : teamRule + ":" + team;

        setLabel(ui, "#DetailNameValue", safe(selectedItem.name()));
        setLabel(ui, "#DetailIdValue", safe(selectedItem.id()));
        setLabel(ui, "#DetailCostValue", String.valueOf(Math.max(0, selectedItem.cost())));
        setLabel(ui, "#DetailAvailabilityValue", safe(selectedItem.availability(), "any"));
        setLabel(ui, "#DetailTeamRuleValue", teamRuleText);
        setLabel(ui, "#DetailRewardsValue", CtfWorkflowFacade.formatRewards(selectedItem));
    }

    private void renderEntries(com.hypixel.hytale.server.core.ui.builder.UICommandBuilder ui,
                               com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,
                               List<CtfShopService.ShopItemView> visibleItems) {
        int visibleCount = (visibleItems == null) ? 0 : visibleItems.size();
        for (int index = 0; index < MAX_ENTRIES; index++) {
            String entry = "#Entry" + index;
            String nameLabel = "#Entry" + index + "Name";
            String costLabel = "#Entry" + index + "Cost";
            String viewAction = "#Entry" + index + "ViewAction";
            String viewButton = "#Entry" + index + "ViewButton";
            String buyAction = "#Entry" + index + "BuyAction";
            String buyButton = "#Entry" + index + "BuyButton";
            if (events != null) {
                bind(events, viewButton, ACTION_VIEW, SLOT_PREFIX + index);
                bind(events, buyButton, ACTION_BUY, SLOT_PREFIX + index);
            }

            if (index >= visibleCount) {
                setVisible(ui, entry, false);
                setVisible(ui, "#Entry" + index + "SelectedAccent", false);
                continue;
            }

            CtfShopService.ShopItemView item = visibleItems.get(index);
            setVisible(ui, entry, true);
            setVisible(ui, "#Entry" + index + "SelectedAccent", item != null
                    && selectedItemId != null
                    && !selectedItemId.isBlank()
                    && selectedItemId.equalsIgnoreCase(safe(item.id())));
            setLabel(ui, nameLabel, safe(item.name()));
            setLabel(ui, costLabel, String.valueOf(Math.max(0, item.cost())));
        }
    }

    private void handleViewAction(PlayerContext context, String value) {
        CtfShopService.ShopItemView item = resolveRequestedItem(context, value);
        if (item == null || safe(item.id()).isBlank()) {
            statusMessage = "That shop item is no longer available.";
            statusIsError = true;
            return;
        }
        selectedItemId = safe(item.id());
        statusMessage = "Viewing " + safe(item.name()) + ".";
        statusIsError = false;
    }

    private CtfShopService.ShopItemView resolveRequestedItem(PlayerContext context, String value) {
        String requested = safe(value);
        String uuid = (context == null) ? null : context.uuid();
        CtfWorkflowFacade.ShopSnapshot snapshot = workflow.snapshotShop(uuid, selectedItemId, MAX_ENTRIES);
        Integer slot = parseSlotIndex(requested);
        if (slot != null) {
            return (slot >= 0 && slot < snapshot.visibleItems().size()) ? snapshot.visibleItems().get(slot) : null;
        }

        for (CtfShopService.ShopItemView item : snapshot.visibleItems()) {
            if (item != null && safe(item.id()).equalsIgnoreCase(requested)) {
                return item;
            }
        }
        return null;
    }

    private static Integer parseSlotIndex(String value) {
        String normalized = safe(value);
        if (normalized.startsWith(SLOT_PREFIX)) {
            normalized = normalized.substring(SLOT_PREFIX.length());
        }
        try {
            int slot = Integer.parseInt(normalized);
            return (slot >= 0 && slot < MAX_ENTRIES) ? slot : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String combineStatus(String left, String right) {
        String safeLeft = safe(left);
        String safeRight = safe(right);
        if (safeLeft.isBlank()) return safeRight;
        if (safeRight.isBlank()) return safeLeft;
        return safeLeft + " " + safeRight;
    }

    private static String safe(String text, String fallback) {
        String safeText = safe(text);
        return safeText.isBlank() ? fallback : safeText;
    }
}
