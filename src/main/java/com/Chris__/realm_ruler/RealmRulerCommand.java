package com.Chris__.realm_ruler;

import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.util.SpawnTeleportUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RealmRulerCommand extends CommandBase {

    private static final Message MSG_USAGE =
            Message.raw("Usage: /rr ctf <join [random|red|blue|yellow|white]|leave|start|stop>");

    private static final Message MSG_NOT_READY =
            Message.raw("[RealmRuler] Not ready yet (plugin still starting?).");

    private static final Message MSG_PLAYERS_ONLY =
            Message.raw("[RealmRuler] Players only.");

    private static final double SPAWN_JITTER_RADIUS_BLOCKS = 3.0d;

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final TargetingService targetingService;

    public RealmRulerCommand(CtfMatchService matchService, SimpleClaimsCtfBridge simpleClaims, TargetingService targetingService) {
        super("RealmRuler", "Controls Realm Ruler minigames.");
        this.setAllowsExtraArguments(true); // we parse ctx.getInputString() ourselves
        this.addAliases("rr");
        this.setPermissionGroup(GameMode.Adventure); // allow anyone (low barrier)
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.targetingService = targetingService;
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

                        // Teleport them back to their team spawn on reconnect
                        if (simpleClaims != null && targetingService != null) {
                            var spawn = simpleClaims.getTeamSpawn(teamName);
                            if (spawn != null) {
                                SpawnTeleportUtil.queueTeamSpawnTeleport(targetingService, uuid, spawn.world(), spawn.x(), spawn.y(), spawn.z(), SPAWN_JITTER_RADIUS_BLOCKS);
                            } else {
                                ctx.sendMessage(Message.raw("[RealmRuler] Team spawn not set for: " + teamName + ". Ask an admin to run: /sc " + teamName.toLowerCase(java.util.Locale.ROOT) + " spawn"));
                            }
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
                // Preflight: ensure spawns exist for active lobby teams before starting.
                if (simpleClaims != null && simpleClaims.isAvailable()) {
                    Map<String, CtfMatchService.Team> lobby = matchService.getLobbyWaitingTeamsSnapshot();
                    Set<CtfMatchService.Team> activeTeams = new HashSet<>(lobby.values());
                    activeTeams.remove(null);

                    List<String> missing = new ArrayList<>();
                    for (CtfMatchService.Team team : activeTeams) {
                        if (team == null) continue;
                        if (simpleClaims.getTeamSpawn(team.displayName()) == null) {
                            missing.add(team.displayName());
                        }
                    }

                    if (!missing.isEmpty()) {
                        ctx.sendMessage(Message.raw("[RealmRuler] Missing spawns for: " + String.join(", ", missing) +
                                ". Set with: /sc red spawn, /sc blue spawn, /sc yellow spawn, /sc white spawn"));
                        return;
                    }
                }

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

                        // Teleport players to their team spawns (jittered within the spawn chunk).
                        if (targetingService != null) {
                            for (Map.Entry<String, CtfMatchService.Team> e : matchService.getActiveMatchTeams().entrySet()) {
                                if (e.getKey() == null || e.getValue() == null) continue;
                                var spawn = simpleClaims.getTeamSpawn(e.getValue().displayName());
                                if (spawn == null) continue; // should be prevented by preflight
                                SpawnTeleportUtil.queueTeamSpawnTeleport(targetingService, e.getKey(), spawn.world(), spawn.x(), spawn.y(), spawn.z(), SPAWN_JITTER_RADIUS_BLOCKS);
                            }
                        }
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
