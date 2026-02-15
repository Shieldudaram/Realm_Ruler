package com.Chris__.realm_ruler.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class LobbyHud extends CustomUIHud {

    private boolean visible = false;
    private String teamName = "";
    private int waitingCount = 0;
    private String waitingTeamsLine = "";

    public LobbyHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    public void show(String teamName, int waitingCount, String waitingTeamsLine) {
        this.visible = true;
        this.teamName = (teamName == null) ? "" : teamName;
        this.waitingCount = Math.max(0, waitingCount);
        this.waitingTeamsLine = (waitingTeamsLine == null) ? "" : waitingTeamsLine;
    }

    public void hide() {
        this.visible = false;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!visible) return;

        ui.append("Hud/Timer/Lobby.ui");

        String teamText = "Team: " + teamName;
        ui.set("#LobbyTeamLabel.Text", teamText);
        ui.set("#LobbyTeamLabel.TextSpans", Message.raw(teamText));

        String waitingText = "Waiting: " + waitingCount;
        ui.set("#LobbyWaitingLabel.Text", waitingText);
        ui.set("#LobbyWaitingLabel.TextSpans", Message.raw(waitingText));

        String teamsText = "Teams: " + waitingTeamsLine;
        ui.set("#LobbyTeamsLabel.Text", teamsText);
        ui.set("#LobbyTeamsLabel.TextSpans", Message.raw(teamsText));
    }
}
