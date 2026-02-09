package com.Chris__.realm_ruler.ui;

import com.Chris__.realm_ruler.integration.MultipleHudBridge;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One authoritative match timer shared by everyone.
 *
 * Call flow:
 *  - start(seconds) / stop() are queued from commands/modes via TimerAction
 *  - tick(dt) is called from TargetingService's "global tick gate" (once per slice)
 *  - renderForPlayer(...) is called once per player tick to show the same remaining time to everyone
 */
public final class GlobalMatchTimerService {
    private static final String HUD_SLOT_ID = "RealmRuler_MatchHud";

    private final MultipleHudBridge multipleHudBridge;
    private final Map<String, GameTimerHud> hudByUuid = new ConcurrentHashMap<>();
    private final Set<String> shownHudByUuid = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> lastShownSecondsByUuid = new ConcurrentHashMap<>();
    private final Map<String, CtfFlagsHudState> lastShownFlagsByUuid = new ConcurrentHashMap<>();

    private volatile Supplier<CtfFlagsHudState> flagsHudStateProvider = null;

    private boolean running = false;
    private int remainingSeconds = 0;
    private float secondAccumulator = 0f;

    public GlobalMatchTimerService(MultipleHudBridge multipleHudBridge) {
        this.multipleHudBridge = multipleHudBridge;
    }

    public void start(int seconds) {
        this.remainingSeconds = Math.max(0, seconds);
        this.secondAccumulator = 0f;
        this.running = (this.remainingSeconds > 0);
        this.lastShownSecondsByUuid.clear(); // force refresh for all players
        this.lastShownFlagsByUuid.clear(); // force refresh for all players
    }

    public void stop() {
        this.running = false;
        this.remainingSeconds = 0;
        this.secondAccumulator = 0f;
        this.lastShownSecondsByUuid.clear(); // force hide refresh
        this.lastShownFlagsByUuid.clear(); // force hide refresh
    }

    /** dt is in seconds. */
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

    /** Call from per-player tick. Cheap because it only refreshes HUD when seconds changes. */
    public void renderForPlayer(String uuid, Player player, PlayerRef playerRef) {
        if (uuid == null || uuid.isEmpty() || player == null || playerRef == null) return;

        GameTimerHud hud = hudByUuid.computeIfAbsent(uuid, k -> new GameTimerHud(playerRef));

        if (!running) {
            if (shownHudByUuid.remove(uuid)) {
                hud.hide();
                multipleHudBridge.hideCustomHud(player, playerRef, HUD_SLOT_ID);
            }
            return;
        }

        Supplier<CtfFlagsHudState> flagsProvider = flagsHudStateProvider;
        CtfFlagsHudState flags = (flagsProvider == null) ? null : flagsProvider.get();

        int lastShown = lastShownSecondsByUuid.getOrDefault(uuid, -1);
        CtfFlagsHudState lastFlags = lastShownFlagsByUuid.get(uuid);
        boolean flagsChanged = (flags == null) ? (lastFlags != null) : !flags.equals(lastFlags);
        boolean isCurrentlyShown = shownHudByUuid.contains(uuid);

        if (lastShown != remainingSeconds || flagsChanged || !isCurrentlyShown) {
            hud.showSeconds(remainingSeconds);
            hud.setFlagsState(flags);

            if (multipleHudBridge.setCustomHud(player, playerRef, HUD_SLOT_ID, hud)) {
                shownHudByUuid.add(uuid);
                lastShownSecondsByUuid.put(uuid, remainingSeconds);
                lastShownFlagsByUuid.put(uuid, flags);
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
        this.lastShownFlagsByUuid.clear(); // force refresh
    }
}
