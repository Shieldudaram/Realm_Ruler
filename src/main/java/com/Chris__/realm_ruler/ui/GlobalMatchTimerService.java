package com.Chris__.Realm_Ruler.ui;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One authoritative match timer shared by everyone.
 *
 * Call flow:
 *  - start(seconds) / stop() are queued from commands/modes via TimerAction
 *  - tick(dt) is called from TargetingService's "global tick gate" (once per slice)
 *  - renderForPlayer(...) is called once per player tick to show the same remaining time to everyone
 */
public final class GlobalMatchTimerService {

    private final Map<String, GameTimerHud> hudByUuid = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastShownSecondsByUuid = new ConcurrentHashMap<>();

    private boolean running = false;
    private int remainingSeconds = 0;
    private float secondAccumulator = 0f;

    public void start(int seconds) {
        this.remainingSeconds = Math.max(0, seconds);
        this.secondAccumulator = 0f;
        this.running = (this.remainingSeconds > 0);
        this.lastShownSecondsByUuid.clear(); // force refresh for all players
    }

    public void stop() {
        this.running = false;
        this.remainingSeconds = 0;
        this.secondAccumulator = 0f;
        this.lastShownSecondsByUuid.clear(); // force hide refresh
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
            // Hide once when stopped
            if (hud.isVisible()) {
                hud.hide();
                player.getHudManager().setCustomHud(playerRef, hud);
            }
            return;
        }

        int lastShown = lastShownSecondsByUuid.getOrDefault(uuid, -1);
        if (lastShown != remainingSeconds) {
            hud.showSeconds(remainingSeconds); // IMPORTANT: matches your GameTimerHud API
            player.getHudManager().setCustomHud(playerRef, hud);
            lastShownSecondsByUuid.put(uuid, remainingSeconds);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }
}
