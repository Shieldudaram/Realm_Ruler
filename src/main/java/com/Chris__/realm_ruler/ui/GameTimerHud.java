package com.Chris__.Realm_Ruler.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public class GameTimerHud extends CustomUIHud {

    private int secondsRemaining = 0;
    private boolean visible = false;

    public GameTimerHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    public void show(int startingSeconds) {
        this.secondsRemaining = Math.max(0, startingSeconds);
        this.visible = true;
    }

    public void hide() {
        this.visible = false;
    }

    public void tickOneSecond() {
        if (!visible) return;
        if (secondsRemaining > 0) secondsRemaining--;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!visible) return;

        ui.append("Hud/Timer/Timer.ui");

        ui.set("#TimerLabel.TextSpans", Message.raw(format(secondsRemaining)));
        // ui.set("#TimerTitle.TextSpans", Message.raw("RUNE TIMER")); // optional if exists
    }


    private static String format(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }
}
