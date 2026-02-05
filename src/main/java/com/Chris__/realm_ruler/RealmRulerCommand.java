package com.Chris__.realm_ruler;

import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

public final class RealmRulerCommand extends CommandBase {

    private static final Message MSG_USAGE =
            Message.raw("Usage: /rr ctf <join [random|red|blue|yellow|white]|leave|start|stop>");

    private static final Message MSG_NOT_READY =
            Message.raw("[RealmRuler] Not ready yet (plugin still starting?).");

    private static final Message MSG_PLAYERS_ONLY =
            Message.raw("[RealmRuler] Players only.");

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;

    public RealmRulerCommand(CtfMatchService matchService, SimpleClaimsCtfBridge simpleClaims) {
        super("RealmRuler", "Controls Realm Ruler minigames.");
        this.setAllowsExtraArguments(true); // we parse ctx.getInputString() ourselves
        this.addAliases("rr");
        this.setPermissionGroup(GameMode.Adventure); // allow anyone (low barrier)
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
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
                if (uuid == null || uuid.isBlank()) {
                    ctx.sendMessage(MSG_PLAYERS_ONLY);
                    return;
                }

                String argTeam = (args.length >= 4) ? args[3] : null;
                CtfMatchService.Team requested = null;
                if (argTeam != null && !argTeam.isBlank() && !"random".equalsIgnoreCase(argTeam)) {
                    requested = CtfMatchService.parseTeam(argTeam);
                    if (requested == null) {
                        ctx.sendMessage(MSG_USAGE);
                        return;
                    }
                }

                CtfMatchService.Team previous = matchService.lobbyTeamFor(uuid);
                CtfMatchService.JoinLobbyResult jr = matchService.joinLobby(uuid, requested);

                if (jr.status() == CtfMatchService.JoinStatus.NOT_READY) {
                    ctx.sendMessage(MSG_NOT_READY);
                    return;
                }

                if (jr.status() == CtfMatchService.JoinStatus.MATCH_RUNNING) {
                    String teamName = safeTeamName(jr);
                    if (!"<unknown>".equals(teamName)) {
                        // Best-effort: ensure their temporary team access exists (useful on reconnect).
                        if (simpleClaims != null) {
                            simpleClaims.setTeam(uuid, teamName);
                        }
                    }
                    ctx.sendMessage(Message.raw("[RealmRuler] CaptureTheFlag match already running (remaining: " + formatSeconds(matchService.getRemainingSeconds()) + "). Team: " + teamName));
                    return;
                }

                if (jr.status() == CtfMatchService.JoinStatus.ALREADY_WAITING) {
                    boolean changed = (requested != null && previous != null && requested != previous);
                    if (changed) {
                        ctx.sendMessage(Message.raw("[RealmRuler] Switched to team: " + safeTeamName(jr) + " (waiting: " + jr.waitingCount() + ")"));
                    } else {
                        ctx.sendMessage(Message.raw("[RealmRuler] You're already waiting in the CaptureTheFlag lobby. Team: " + safeTeamName(jr) + " (waiting: " + jr.waitingCount() + ")"));
                    }
                    return;
                }

                ctx.sendMessage(Message.raw("[RealmRuler] Joined CaptureTheFlag lobby. Team: " + safeTeamName(jr) + " (waiting: " + jr.waitingCount() + ")"));
                return;
            }

            if ("leave".equalsIgnoreCase(action)) {
                String uuid = (ctx.sender() == null || ctx.sender().getUuid() == null) ? null : ctx.sender().getUuid().toString();
                if (uuid == null || uuid.isBlank()) {
                    ctx.sendMessage(MSG_PLAYERS_ONLY);
                    return;
                }

                if (matchService.isRunning()) {
                    ctx.sendMessage(Message.raw("[RealmRuler] Can't leave the lobby while a match is running."));
                    return;
                }

                boolean removed = matchService.leaveLobby(uuid);
                ctx.sendMessage(removed
                        ? Message.raw("[RealmRuler] Left CaptureTheFlag lobby.")
                        : Message.raw("[RealmRuler] You're not in the CaptureTheFlag lobby."));
                return;
            }

            if ("start".equalsIgnoreCase(action)) {
                CtfMatchService.StartResult res = matchService.startCaptureTheFlag();
                if (res == CtfMatchService.StartResult.STARTED) {
                    if (simpleClaims != null) {
                        Map<String, String> teamNameByUuid = new HashMap<>();
                        for (Map.Entry<String, CtfMatchService.Team> e : matchService.getActiveMatchTeams().entrySet()) {
                            if (e.getKey() == null || e.getValue() == null) continue;
                            teamNameByUuid.put(e.getKey(), e.getValue().displayName());
                        }
                        simpleClaims.ensureParties();
                        simpleClaims.applyTeams(teamNameByUuid);
                    }
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

            if ("stop".equalsIgnoreCase(action)) {
                if (!matchService.isRunning()) {
                    ctx.sendMessage(Message.raw("[RealmRuler] No CaptureTheFlag match is running."));
                    return;
                }

                matchService.stopCaptureTheFlag();
                if (simpleClaims != null) {
                    simpleClaims.clearTeams(matchService.getActiveMatchUuids());
                }
                ctx.sendMessage(Message.raw("[RealmRuler] Stopped CaptureTheFlag match."));
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
