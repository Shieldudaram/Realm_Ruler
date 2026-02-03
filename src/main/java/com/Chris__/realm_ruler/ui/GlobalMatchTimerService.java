package com.Chris__.Realm_Ruler.ui;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlobalMatchTimerService {

    private final Map<String, GameTimerHud> hudByUuid = new ConcurrentHashMap<>();

    private boolean running = false;
    private int secondsRemaining = 0;
    private float accum = 0f;

    /** Start a single shared match timer for everyone. */
    public void start(int seconds) {
        this.running = true;
        this.secondsRemaining = Math.max(0, seconds);
        this.accum = 0f;
    }

    public void stop() {
        this.running = false;
        this.secondsRemaining = 0;
        this.accum = 0f;
    }

    /** One tick per server tick (not per player). */
    public void tick(float dt) {
        if (!running) return;

        accum += dt;
        while (accum >= 1.0f) {
            accum -= 1.0f;
            if (secondsRemaining > 0) secondsRemaining--;
            if (secondsRemaining <= 0) {
                secondsRemaining = 0;
                running = false;
                break;
            }
        }
    }

    /** Called per player tick (cheap): just render the shared time for that player. */
    public void renderForPlayer(String uuid, Player player, PlayerRef playerRef) {
        if (!running && secondsRemaining <= 0) return;

        GameTimerHud hud = hudByUuid.computeIfAbsent(uuid, k -> new GameTimerHud(playerRef));
        hud.show(secondsRemaining);

        // Your API expects (playerRef, hud) based on what you already fixed
        player.getHudManager().setCustomHud(playerRef, hud);
    }
}
