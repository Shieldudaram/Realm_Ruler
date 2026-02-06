package com.Chris__.realm_ruler.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public class GameTimerHud extends CustomUIHud {

    private int secondsRemaining = 0;
    private boolean visible = false;
    private CtfFlagsHudState flagsState = null;

    public GameTimerHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    public void showSeconds(int seconds) {
        this.secondsRemaining = Math.max(0, seconds);
        this.visible = true;
    }

    public void setFlagsState(CtfFlagsHudState state) {
        this.flagsState = state;
    }

    public void hide() {
        this.visible = false;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!visible) return;

        ui.append("Hud/Timer/Timer.ui");
        ui.append("Hud/CTF/Flags.ui");
        String text = format(secondsRemaining);
        ui.set("#TimerLabel.Text", text);
        ui.set("#TimerLabel.TextSpans", Message.raw(text));
        // Optional if your UI has this id:
        // ui.set("#TimerTitle.TextSpans", Message.raw("CAPTURE THE FLAG TIMER"));

        CtfFlagsHudState f = flagsState;
        if (f != null) {
            ui.set("#RedFlagStatus.TextSpans", Message.raw(safe(f.red())));
            ui.set("#BlueFlagStatus.TextSpans", Message.raw(safe(f.blue())));
            ui.set("#YellowFlagStatus.TextSpans", Message.raw(safe(f.yellow())));
            ui.set("#WhiteFlagStatus.TextSpans", Message.raw(safe(f.white())));
        } else {
            ui.set("#RedFlagStatus.TextSpans", Message.raw(""));
            ui.set("#BlueFlagStatus.TextSpans", Message.raw(""));
            ui.set("#YellowFlagStatus.TextSpans", Message.raw(""));
            ui.set("#WhiteFlagStatus.TextSpans", Message.raw(""));
        }
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String format(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }
}
