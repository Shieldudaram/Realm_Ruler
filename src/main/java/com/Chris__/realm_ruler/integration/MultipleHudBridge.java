package com.Chris__.realm_ruler.integration;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MultipleHudBridge {
    private static final Message HUD_DISABLED_MESSAGE = Message.raw(
            "[RealmRuler] HUD integration disabled. MultipleHUD is missing or incompatible. Contact an admin."
    );

    private static final String[] PLUGIN_LOOKUP_NAMES = new String[]{
            "Buuz135:MultipleHUD",
            "MultipleHUD",
            "buuz135:multiplehud",
            "Buuz135:multiplehud"
    };

    private static final String[] PLUGIN_LOOKUP_METHODS = new String[]{
            "getPlugin",
            "getPluginByName",
            "getPluginOrNull"
    };

    private final HytaleLogger logger;
    private final AtomicBoolean runtimeFailed = new AtomicBoolean(false);
    private final Set<String> warnedPlayerUuids = ConcurrentHashMap.newKeySet();

    private volatile Object multipleHudPlugin;
    private volatile Method setCustomHudMethod;
    private volatile Method hideCustomHudMethod;

    public MultipleHudBridge(HytaleLogger logger) {
        this.logger = logger;
    }

    public void requireReadyOrThrow() {
        String error = ensureLoaded();
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    public boolean setCustomHud(Player player, PlayerRef playerRef, String slotId, CustomUIHud hud) {
        if (player == null || playerRef == null || slotId == null || slotId.isBlank() || hud == null) return false;
        String error = ensureLoaded();
        if (error != null) {
            markRuntimeFailure("MultipleHUD bridge unavailable: " + error, null);
            warnPlayerOnce(player, playerRef);
            return false;
        }

        try {
            setCustomHudMethod.invoke(multipleHudPlugin, player, playerRef, slotId, hud);
            return true;
        } catch (Throwable t) {
            markRuntimeFailure("Failed invoking MultipleHUD#setCustomHud for slot '" + slotId + "'.", t);
            warnPlayerOnce(player, playerRef);
            return false;
        }
    }

    public boolean hideCustomHud(Player player, PlayerRef playerRef, String slotId) {
        if (player == null || playerRef == null || slotId == null || slotId.isBlank()) return false;
        String error = ensureLoaded();
        if (error != null) {
            markRuntimeFailure("MultipleHUD bridge unavailable: " + error, null);
            warnPlayerOnce(player, playerRef);
            return false;
        }

        try {
            hideCustomHudMethod.invoke(multipleHudPlugin, player, slotId);
            return true;
        } catch (Throwable t) {
            markRuntimeFailure("Failed invoking MultipleHUD#hideCustomHud for slot '" + slotId + "'.", t);
            warnPlayerOnce(player, playerRef);
            return false;
        }
    }

    public void clearWarnedPlayer(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        warnedPlayerUuids.remove(uuid);
    }

    public boolean isRuntimeFailed() {
        return runtimeFailed.get();
    }

    private String ensureLoaded() {
        if (runtimeFailed.get()) {
            return "bridge is disabled after a runtime integration failure";
        }

        if (multipleHudPlugin != null && setCustomHudMethod != null && hideCustomHudMethod != null) {
            return null;
        }

        synchronized (this) {
            if (runtimeFailed.get()) {
                return "bridge is disabled after a runtime integration failure";
            }
            if (multipleHudPlugin != null && setCustomHudMethod != null && hideCustomHudMethod != null) {
                return null;
            }

            Object pluginManager = PluginManager.get();
            if (pluginManager == null) {
                return "PluginManager.get() returned null while resolving MultipleHUD";
            }

            Object plugin = findPluginBestEffort(pluginManager);
            if (plugin == null) {
                return "MultipleHUD plugin was not found. Expected plugin id: Buuz135:MultipleHUD";
            }

            try {
                Method setHud = plugin.getClass().getMethod(
                        "setCustomHud",
                        Player.class,
                        PlayerRef.class,
                        String.class,
                        CustomUIHud.class
                );
                Method hideHud = plugin.getClass().getMethod(
                        "hideCustomHud",
                        Player.class,
                        String.class
                );

                this.multipleHudPlugin = plugin;
                this.setCustomHudMethod = setHud;
                this.hideCustomHudMethod = hideHud;
                logger.atInfo().log("[RR-HUD] MultipleHUD bridge ready. plugin=%s", plugin.getClass().getName());
                return null;
            } catch (Throwable t) {
                return "MultipleHUD plugin was found, but required methods are missing or incompatible: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            }
        }
    }

    private Object findPluginBestEffort(Object pluginManager) {
        for (String name : PLUGIN_LOOKUP_NAMES) {
            for (String methodName : PLUGIN_LOOKUP_METHODS) {
                try {
                    Method m = pluginManager.getClass().getMethod(methodName, String.class);
                    Object plugin = m.invoke(pluginManager, name);
                    if (plugin != null) return plugin;
                } catch (Throwable ignored) {
                }
            }
        }

        try {
            Method getPlugins = pluginManager.getClass().getMethod("getPlugins");
            Object plugins = getPlugins.invoke(pluginManager);
            if (plugins instanceof Iterable<?> iterable) {
                for (Object plugin : iterable) {
                    if (plugin == null) continue;
                    String asText = plugin.toString().toLowerCase(Locale.ROOT);
                    for (String candidate : PLUGIN_LOOKUP_NAMES) {
                        if (asText.contains(candidate.toLowerCase(Locale.ROOT))) {
                            return plugin;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void markRuntimeFailure(String message, Throwable cause) {
        if (!runtimeFailed.compareAndSet(false, true)) return;
        if (cause != null) {
            logger.atSevere().withCause(cause).log("[RR-HUD] %s", message);
        } else {
            logger.atSevere().log("[RR-HUD] %s", message);
        }
    }

    private void warnPlayerOnce(Player player, PlayerRef playerRef) {
        if (player == null || playerRef == null) return;
        UUID uuid = playerRef.getUuid();
        if (uuid == null) return;
        String uuidText = uuid.toString();
        if (!warnedPlayerUuids.add(uuidText)) return;

        try {
            player.sendMessage(HUD_DISABLED_MESSAGE);
        } catch (Throwable ignored) {
        }
    }
}
