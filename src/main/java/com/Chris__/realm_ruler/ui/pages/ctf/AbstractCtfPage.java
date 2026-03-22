package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

public abstract class AbstractCtfPage extends InteractiveCustomUIPage<CtfPageEventData> {

    protected record PlayerContext(Player player, PlayerRef playerRef, String uuid) {
    }

    protected final CtfWorkflowFacade workflow;
    private final HytaleLogger logger;

    protected AbstractCtfPage(@Nonnull PlayerRef playerRef,
                              CtfWorkflowFacade workflow,
                              HytaleLogger logger) {
        super(playerRef, CustomPageLifetime.CanDismiss, CtfPageEventData.CODEC);
        this.workflow = workflow;
        this.logger = logger;
    }

    protected final void sendSafeUpdate(Consumer<UICommandBuilder> renderUpdate, String failureLog) {
        UICommandBuilder update = new UICommandBuilder();
        if (renderUpdate != null) {
            renderUpdate.accept(update);
        }
        try {
            sendUpdate(update, false);
        } catch (Throwable t) {
            if (logger != null) {
                logger.atWarning().withCause(t).log("%s", safe(failureLog));
            }
        }
    }

    protected final void openPage(PlayerContext context,
                                  Ref<EntityStore> ref,
                                  Store<EntityStore> store,
                                  CustomUIPage page) {
        if (context == null || context.player() == null || page == null) return;
        context.player().getPageManager().openCustomPage(ref, store, page);
    }

    protected final PlayerContext resolvePlayerContext(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) return null;
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        String uuid = (playerRef == null || playerRef.getUuid() == null) ? null : playerRef.getUuid().toString();
        if (player == null || playerRef == null || uuid == null || uuid.isBlank()) return null;
        return new PlayerContext(player, playerRef, uuid);
    }

    protected final String resolveUuid(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) return null;
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getUuid() == null) return null;
        return playerRef.getUuid().toString();
    }

    protected final void sendFallbackMessage(Player player, String message) {
        if (player == null) return;
        String safeMessage = safe(message);
        if (safeMessage.isBlank()) return;
        try {
            player.sendMessage(Message.raw("[RealmRuler] " + safeMessage));
        } catch (Throwable ignored) {
        }
    }

    protected static void bind(UIEventBuilder events, String selector, String action) {
        bind(events, selector, action, "");
    }

    protected static void bind(UIEventBuilder events, String selector, String action, String value) {
        if (events == null || selector == null || selector.isBlank()) return;
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector,
                EventData.of("Action", safe(action)).append("Value", safe(value))
        );
    }

    protected static void setLabel(UICommandBuilder ui, String selector, String text) {
        if (ui == null || selector == null || selector.isBlank()) return;
        String safeText = safe(text);
        ui.set(selector + ".Text", safeText);
        ui.set(selector + ".TextSpans", Message.raw(safeText));
    }

    protected static void setVisible(UICommandBuilder ui, String selector, boolean visible) {
        if (ui == null || selector == null || selector.isBlank()) return;
        ui.set(selector + ".Visible", visible);
    }

    protected static void setStatus(UICommandBuilder ui,
                                    String containerSelector,
                                    String labelSelector,
                                    String message,
                                    boolean error) {
        String text = statusText(message, error);
        boolean visible = !text.isBlank();
        setVisible(ui, containerSelector, visible);
        setVisible(ui, labelSelector, visible);
        setLabel(ui, labelSelector, text);
    }

    protected static String pointsLabel(int points) {
        return "Points: " + Math.max(0, points);
    }

    protected static String statusText(String message, boolean error) {
        String safeMessage = safe(message);
        if (safeMessage.isBlank()) return "";
        return error ? "Error: " + safeMessage : safeMessage;
    }

    protected static String safe(String text) {
        return (text == null) ? "" : text.trim();
    }
}
