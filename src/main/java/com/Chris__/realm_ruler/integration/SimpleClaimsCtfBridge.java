package com.Chris__.realm_ruler.integration;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SimpleClaimsCtfBridge {

    private final HytaleLogger logger;

    private Object simpleClaimsPlugin;
    private Method ensureCtfParties;
    private Method setPlayerCtfTeam;
    private Method clearPlayerCtfTeam;
    private Method clearAllCtfTeams;

    private boolean loggedMissing = false;

    public SimpleClaimsCtfBridge(HytaleLogger logger) {
        this.logger = logger;
    }

    public boolean isAvailable() {
        return ensureLoaded();
    }

    public void ensureParties() {
        if (!ensureLoaded()) return;
        try {
            ensureCtfParties.invoke(simpleClaimsPlugin);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-SC] Failed to ensure CTF team parties.");
        }
    }

    public void applyTeams(Map<String, String> teamNameByUuid) {
        if (teamNameByUuid == null || teamNameByUuid.isEmpty()) return;
        if (!ensureLoaded()) return;
        for (Map.Entry<String, String> e : teamNameByUuid.entrySet()) {
            String uuidStr = e.getKey();
            String teamName = e.getValue();
            if (uuidStr == null || uuidStr.isBlank() || teamName == null || teamName.isBlank()) continue;
            setTeam(uuidStr, teamName);
        }
    }

    public void clearTeams(Iterable<String> uuids) {
        if (uuids == null) return;
        if (!ensureLoaded()) return;
        for (String uuid : uuids) {
            clearTeam(uuid);
        }
    }

    public boolean setTeam(String uuid, String teamName) {
        if (!ensureLoaded()) return false;
        UUID id = parseUuid(uuid);
        if (id == null) return false;
        try {
            Object res = setPlayerCtfTeam.invoke(simpleClaimsPlugin, id, teamName);
            return !(res instanceof Boolean b) || b;
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-SC] Failed to set player team. uuid=%s team=%s", uuid, teamName);
            return false;
        }
    }

    public void clearTeam(String uuid) {
        if (!ensureLoaded()) return;
        UUID id = parseUuid(uuid);
        if (id == null) return;
        try {
            clearPlayerCtfTeam.invoke(simpleClaimsPlugin, id);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-SC] Failed to clear player team. uuid=%s", uuid);
        }
    }

    public void clearAllTeams() {
        if (!ensureLoaded()) return;
        try {
            clearAllCtfTeams.invoke(simpleClaimsPlugin);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-SC] Failed to clear all teams.");
        }
    }

    private boolean ensureLoaded() {
        if (simpleClaimsPlugin != null) return true;

        try {
            Object pm = PluginManager.get();
            Object plugin = findPluginBestEffort(pm,
                    "Buuz135:SimpleClaims",
                    "SimpleClaims",
                    "buuz135:simpleclaims",
                    "Buuz135:simpleclaims"
            );
            if (plugin == null) {
                if (!loggedMissing) {
                    loggedMissing = true;
                    logger.atWarning().log("[RR-SC] SimpleClaims not found via PluginManager. Is it installed and enabled?");
                }
                return false;
            }

            Method ensure = plugin.getClass().getMethod("rrEnsureCtfTeamParties");
            Method setTeam = plugin.getClass().getMethod("rrSetPlayerCtfTeam", UUID.class, String.class);
            Method clearTeam = plugin.getClass().getMethod("rrClearPlayerCtfTeam", UUID.class);
            Method clearAll = plugin.getClass().getMethod("rrClearAllCtfTeams");

            this.simpleClaimsPlugin = plugin;
            this.ensureCtfParties = ensure;
            this.setPlayerCtfTeam = setTeam;
            this.clearPlayerCtfTeam = clearTeam;
            this.clearAllCtfTeams = clearAll;
            logger.atInfo().log("[RR-SC] SimpleClaims bridge ready. plugin=%s", plugin.getClass().getName());
            return true;
        } catch (Throwable t) {
            if (!loggedMissing) {
                loggedMissing = true;
                logger.atWarning().withCause(t).log("[RR-SC] Failed to initialize SimpleClaims bridge.");
            }
            return false;
        }
    }

    private static @Nullable UUID parseUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return UUID.fromString(uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findPluginBestEffort(Object pluginManager, String... names) {
        String[] methodNames = new String[]{"getPlugin", "getPluginByName", "getPluginOrNull"};

        for (String name : names) {
            for (String mName : methodNames) {
                try {
                    Method m = pluginManager.getClass().getMethod(mName, String.class);
                    Object p = m.invoke(pluginManager, name);
                    if (p != null) return p;
                } catch (Throwable ignored) {
                }
            }
        }

        try {
            Method m = pluginManager.getClass().getMethod("getPlugins");
            Object plugins = m.invoke(pluginManager);
            if (plugins instanceof Iterable<?> it) {
                for (Object p : it) {
                    if (p == null) continue;
                    String s = p.toString().toLowerCase(Locale.ROOT);
                    for (String wanted : names) {
                        if (s.contains(wanted.toLowerCase(Locale.ROOT))) return p;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}

