package com.Chris__.realm_ruler;

import com.Chris__.realm_ruler.match.CtfMatchService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;

public final class RealmRulerCommand extends CommandBase {

    private static final Message MSG_USAGE =
            Message.raw("Usage: /rr ctf <join|start>");

    private static final Message MSG_NOT_READY =
            Message.raw("[RealmRuler] Not ready yet (plugin still starting?).");

    private final CtfMatchService matchService;

    public RealmRulerCommand(CtfMatchService matchService) {
        super("RealmRuler", "Controls Realm Ruler minigames.");
        this.setAllowsExtraArguments(true); // we parse ctx.getInputString() ourselves
        this.addAliases("rr");
        this.setPermissionGroup(GameMode.Adventure); // allow anyone (low barrier)
        this.matchService = matchService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String[] args = splitArgs(ctx.getInputString());
        if (args.length < 3) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String sub = args[1];
        String action = args[2];

        if (isCtf(sub)) {
            if (matchService == null) {
                ctx.sendMessage(MSG_NOT_READY);
                return;
            }

            if ("join".equalsIgnoreCase(action)) {
                String uuid = (ctx.sender() == null || ctx.sender().getUuid() == null) ? null : ctx.sender().getUuid().toString();
                CtfMatchService.JoinLobbyResult jr = matchService.joinLobby(uuid);

                if (jr.status() == CtfMatchService.JoinStatus.NOT_READY) {
                    ctx.sendMessage(MSG_NOT_READY);
                    return;
                }

                if (jr.status() == CtfMatchService.JoinStatus.MATCH_RUNNING) {
                    ctx.sendMessage(Message.raw("[RealmRuler] CaptureTheFlag match already running (remaining: " + formatSeconds(matchService.getRemainingSeconds()) + ")."));
                    return;
                }

                if (jr.status() == CtfMatchService.JoinStatus.ALREADY_WAITING) {
                    ctx.sendMessage(Message.raw("[RealmRuler] You're already waiting in the CaptureTheFlag lobby. Team: " + safeTeamName(jr) + " (waiting: " + jr.waitingCount() + ")"));
                    return;
                }

                ctx.sendMessage(Message.raw("[RealmRuler] Joined CaptureTheFlag lobby. Team: " + safeTeamName(jr) + " (waiting: " + jr.waitingCount() + ")"));
                return;
            }

            if ("start".equalsIgnoreCase(action)) {
                CtfMatchService.StartResult res = matchService.startCaptureTheFlag();
                if (res == CtfMatchService.StartResult.STARTED) {
                    ctx.sendMessage(Message.raw("[RealmRuler] Started CaptureTheFlag match timer: 15:00"));
                    return;
                }

                if (res == CtfMatchService.StartResult.ALREADY_RUNNING) {
                    ctx.sendMessage(Message.raw("[RealmRuler] CaptureTheFlag match already running (remaining: " + formatSeconds(matchService.getRemainingSeconds()) + ")"));
                    return;
                }

                ctx.sendMessage(MSG_NOT_READY);
                return;
            }

            ctx.sendMessage(MSG_USAGE);
            return;
        }

        ctx.sendMessage(MSG_USAGE);
    }

    private static String[] splitArgs(String input) {
        if (input == null) return new String[0];
        String s = input.trim();
        if (s.startsWith("/")) s = s.substring(1).trim();
        return s.isEmpty() ? new String[0] : s.split("\\s+");
    }

    private static String formatSeconds(int totalSeconds) {
        int t = Math.max(0, totalSeconds);
        int m = t / 60;
        int s = t % 60;
        return String.format("%02d:%02d", m, s);
    }

    private static boolean isCtf(String s) {
        if (s == null) return false;
        return "ctf".equalsIgnoreCase(s) || "capturetheflag".equalsIgnoreCase(s);
    }

    private static String safeTeamName(CtfMatchService.JoinLobbyResult jr) {
        if (jr == null || jr.team() == null) return "<unknown>";
        return jr.team().displayName();
    }
}
