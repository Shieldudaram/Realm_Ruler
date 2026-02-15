package com.Chris__.realm_ruler.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class RealmRulerHud extends CustomUIHud {
    private enum Mode {
        HIDDEN,
        LOBBY,
        MATCH
    }

    private Mode mode = Mode.HIDDEN;
    private String teamName = "";
    private int waitingCount = 0;
    private String waitingTeamsLine = "";

    private int secondsRemaining = 0;
    private CtfFlagsHudState flagsState = null;

    public RealmRulerHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    public void showLobby(String teamName, int waitingCount, String waitingTeamsLine) {
        this.mode = Mode.LOBBY;
        this.teamName = (teamName == null) ? "" : teamName;
        this.waitingCount = Math.max(0, waitingCount);
        this.waitingTeamsLine = (waitingTeamsLine == null) ? "" : waitingTeamsLine;
    }

    public void showMatch(int secondsRemaining, CtfFlagsHudState flagsState) {
        this.mode = Mode.MATCH;
        this.secondsRemaining = Math.max(0, secondsRemaining);
        this.flagsState = flagsState;
    }

    public void hide() {
        this.mode = Mode.HIDDEN;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (mode == Mode.HIDDEN) return;

        if (mode == Mode.LOBBY) {
            ui.append(CtfUiAssetContract.HUD_LOBBY);

            String teamText = "Team: " + teamName;
            ui.set("#LobbyTeamLabel.Text", teamText);
            ui.set("#LobbyTeamLabel.TextSpans", Message.raw(teamText));

            String waitingText = "Waiting: " + waitingCount;
            ui.set("#LobbyWaitingLabel.Text", waitingText);
            ui.set("#LobbyWaitingLabel.TextSpans", Message.raw(waitingText));

            String teamsText = "Teams: " + waitingTeamsLine;
            ui.set("#LobbyTeamsLabel.Text", teamsText);
            ui.set("#LobbyTeamsLabel.TextSpans", Message.raw(teamsText));
            return;
        }

        ui.append(CtfUiAssetContract.HUD_TIMER);
        ui.append(CtfUiAssetContract.HUD_FLAGS);

        String timerText = format(secondsRemaining);
        ui.set("#TimerLabel.Text", timerText);
        ui.set("#TimerLabel.TextSpans", Message.raw(timerText));

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
