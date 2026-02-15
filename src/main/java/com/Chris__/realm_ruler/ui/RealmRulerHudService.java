package com.Chris__.realm_ruler.ui;

import com.Chris__.realm_ruler.core.LobbyHudState;
import com.Chris__.realm_ruler.integration.MultipleHudBridge;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class RealmRulerHudService {
    private static final String HUD_SLOT_ID = "RealmRuler_MainHud";

    private record RenderKey(String mode,
                             int seconds,
                             CtfFlagsHudState flags,
                             String teamName,
                             int waitingCount,
                             String waitingTeamsLine) {
    }

    private final MultipleHudBridge multipleHudBridge;
    private final HytaleLogger logger;
    private final AtomicBoolean runtimeDisableLogged = new AtomicBoolean(false);

    private final Map<String, RealmRulerHud> hudByUuid = new ConcurrentHashMap<>();
    private final Set<String> shownHudByUuid = ConcurrentHashMap.newKeySet();
    private final Map<String, RenderKey> lastRenderedByUuid = new ConcurrentHashMap<>();

    private volatile Supplier<CtfFlagsHudState> flagsHudStateProvider = null;
    private volatile boolean hudRenderingEnabled;

    private boolean running = false;
    private int remainingSeconds = 0;
    private float secondAccumulator = 0f;

    public RealmRulerHudService(MultipleHudBridge multipleHudBridge,
                                HytaleLogger logger,
                                boolean hudRenderingEnabled) {
        this.multipleHudBridge = multipleHudBridge;
        this.logger = logger;
        this.hudRenderingEnabled = hudRenderingEnabled;
    }

    public void start(int seconds) {
        this.remainingSeconds = Math.max(0, seconds);
        this.secondAccumulator = 0f;
        this.running = this.remainingSeconds > 0;
        this.lastRenderedByUuid.clear();
    }

    public void stop() {
        this.running = false;
        this.remainingSeconds = 0;
        this.secondAccumulator = 0f;
        this.lastRenderedByUuid.clear();
    }

    public void tick(float dt) {
        if (!running) return;
        if (dt <= 0f) return;

        secondAccumulator += dt;
        while (secondAccumulator >= 1.0f && remainingSeconds > 0) {
            secondAccumulator -= 1.0f;
            remainingSeconds--;

            if (remainingSeconds <= 0) {
                remainingSeconds = 0;
                running = false;
                break;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public void setFlagsHudStateProvider(Supplier<CtfFlagsHudState> provider) {
        this.flagsHudStateProvider = provider;
        this.lastRenderedByUuid.clear();
    }

    public boolean isHudRenderingEnabled() {
        return hudRenderingEnabled;
    }

    public void renderForPlayer(String uuid, Player player, PlayerRef playerRef, LobbyHudState lobbyState) {
        if (uuid == null || uuid.isEmpty() || player == null || playerRef == null) return;

        RealmRulerHud hud = hudByUuid.computeIfAbsent(uuid, ignored -> new RealmRulerHud(playerRef));
        RenderKey desired = desiredKey(lobbyState);

        if (desired == null) {
            lastRenderedByUuid.remove(uuid);
            if (shownHudByUuid.remove(uuid)) {
                hud.hide();
                if (hudRenderingEnabled && !multipleHudBridge.hideCustomHud(player, playerRef, HUD_SLOT_ID)
                        && multipleHudBridge.isRuntimeFailed()) {
                    disableHudRendering("MultipleHUD bridge runtime failure while hiding HUD.");
                }
            }
            return;
        }

        RenderKey previous = lastRenderedByUuid.get(uuid);
        boolean currentlyShown = shownHudByUuid.contains(uuid);
        if (desired.equals(previous) && currentlyShown) return;

        if ("LOBBY".equals(desired.mode())) {
            hud.showLobby(desired.teamName(), desired.waitingCount(), desired.waitingTeamsLine());
        } else {
            hud.showMatch(desired.seconds(), desired.flags());
        }

        lastRenderedByUuid.put(uuid, desired);

        if (!hudRenderingEnabled) return;

        boolean applied = multipleHudBridge.setCustomHud(player, playerRef, HUD_SLOT_ID, hud);
        if (applied) {
            shownHudByUuid.add(uuid);
            return;
        }

        if (multipleHudBridge.isRuntimeFailed()) {
            disableHudRendering("MultipleHUD bridge runtime failure while applying HUD.");
        }
    }

    private RenderKey desiredKey(LobbyHudState lobbyState) {
        if (running) {
            Supplier<CtfFlagsHudState> flagsProvider = flagsHudStateProvider;
            CtfFlagsHudState flags = null;
            if (flagsProvider != null) {
                try {
                    flags = flagsProvider.get();
                } catch (Throwable ignored) {
                    flags = null;
                }
            }
            return new RenderKey("MATCH", remainingSeconds, flags, "", 0, "");
        }

        if (lobbyState == null || !lobbyState.visible()) {
            return null;
        }

        String teamName = (lobbyState.teamName() == null) ? "" : lobbyState.teamName();
        int waitingCount = Math.max(0, lobbyState.waitingCount());
        String waitingTeamsLine = (lobbyState.waitingTeamsLine() == null) ? "" : lobbyState.waitingTeamsLine();
        return new RenderKey("LOBBY", 0, null, teamName, waitingCount, waitingTeamsLine);
    }

    private void disableHudRendering(String reason) {
        if (!hudRenderingEnabled) return;
        hudRenderingEnabled = false;
        shownHudByUuid.clear();
        if (runtimeDisableLogged.compareAndSet(false, true)) {
            logger.atWarning().log("[RR-HUD] %s HUD rendering disabled for this session.", reason);
        }
    }
}
