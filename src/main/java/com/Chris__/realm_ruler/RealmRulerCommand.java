package com.Chris__.realm_ruler;

import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.match.CtfFlagStateService;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.match.CtfPointsRepository;
import com.Chris__.realm_ruler.match.CtfStandRegistryRepository;
import com.Chris__.realm_ruler.npc.NpcArenaRepository;
import com.Chris__.realm_ruler.npc.NpcTestService;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.ui.pages.ctf.CtfShopUiService;
import com.Chris__.realm_ruler.util.SpawnTeleportUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RealmRulerCommand extends CommandBase {

    private static final Message MSG_USAGE =
            Message.raw("Usage: /rr ctf <join [random|red|blue|yellow|white]|leave|start [minutes]|stop|points|shop|stand <add|remove|list|primary> ...> | /rr npc <arena|spawn|despawn|clear>");

    private static final Message MSG_NOT_READY =
            Message.raw("[RealmRuler] Not ready yet (plugin still starting?).");

    private static final Message MSG_PLAYERS_ONLY =
            Message.raw("[RealmRuler] Players only.");
    private static final Message MSG_NO_STAND_PERMISSION =
            Message.raw("[RealmRuler] Missing permission: realmruler.ctf.stand.manage");
    private static final Message MSG_NO_NPC_PERMISSION =
            Message.raw("[RealmRuler] Missing permission: realmruler.npc.manage");
    private static final Message MSG_NPC_USAGE =
            Message.raw("Usage: /rr npc arena <create|pos1|pos2|info|list|delete> ... | /rr npc <spawn|despawn> <arenaId> <npcName> | /rr npc clear [arenaId]");

    private static final double SPAWN_JITTER_RADIUS_BLOCKS = 3.0d;

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final TargetingService targetingService;
    private final CtfFlagStateService flagStateService;
    private final CtfStandRegistryRepository standRegistry;
    private final CtfPointsRepository pointsRepository;
    private final CtfShopUiService shopUiService;
    private final NpcArenaRepository npcArenaRepository;
    private final NpcTestService npcTestService;

    public RealmRulerCommand(CtfMatchService matchService,
                             SimpleClaimsCtfBridge simpleClaims,
                             TargetingService targetingService,
                             CtfFlagStateService flagStateService,
                             CtfStandRegistryRepository standRegistry,
                             CtfPointsRepository pointsRepository,
                             CtfShopUiService shopUiService,
                             NpcArenaRepository npcArenaRepository,
                             NpcTestService npcTestService) {
        super("RealmRuler", "Controls Realm Ruler minigames.");
        this.setAllowsExtraArguments(true); // we parse ctx.getInputString() ourselves
        this.addAliases("rr");
        this.setPermissionGroup(GameMode.Adventure); // allow anyone (low barrier)
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.targetingService = targetingService;
        this.flagStateService = flagStateService;
        this.standRegistry = standRegistry;
        this.pointsRepository = pointsRepository;
        this.shopUiService = shopUiService;
        this.npcArenaRepository = npcArenaRepository;
        this.npcTestService = npcTestService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String[] args = splitArgs(ctx.getInputString());
        if (args.length < 2) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String sub = args[1];

        if (isCtf(sub)) {
            if (args.length < 3) {
                ctx.sendMessage(MSG_USAGE);
                return;
            }
            String action = args[2];

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

            if ("stand".equalsIgnoreCase(action)) {
                handleStandCommand(ctx, args);
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
                int minutes = 15;
                if (args.length >= 4) {
                    try {
                        minutes = Integer.parseInt(args[3].trim());
                    } catch (Throwable ignored) {
                        ctx.sendMessage(MSG_USAGE);
                        return;
                    }
                }

                if (minutes <= 0) {
                    ctx.sendMessage(MSG_USAGE);
                    return;
                }

                long secondsLong = minutes * 60L;
                int seconds = (secondsLong > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) secondsLong;

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

                CtfMatchService.StartResult res = matchService.startCaptureTheFlag(seconds);
                if (res == CtfMatchService.StartResult.STARTED) {
                    if (flagStateService != null) {
                        flagStateService.resetForNewMatch();
                    }

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
                    ctx.sendMessage(Message.raw("[RealmRuler] Started CaptureTheFlag match timer: " + formatSeconds(seconds)));
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
                ctx.sendMessage(Message.raw("[RealmRuler] Stopping CaptureTheFlag match..."));
                return;
            }

            if ("points".equalsIgnoreCase(action)) {
                String uuid = (ctx.sender() == null || ctx.sender().getUuid() == null) ? null : ctx.sender().getUuid().toString();
                if (uuid == null || uuid.isBlank()) {
                    ctx.sendMessage(MSG_PLAYERS_ONLY);
                    return;
                }
                int points = (pointsRepository == null) ? 0 : pointsRepository.getPoints(uuid);
                ctx.sendMessage(Message.raw("[RealmRuler] CTF points: " + points));
                return;
            }

            if ("shop".equalsIgnoreCase(action)) {
                String uuid = (ctx.sender() == null || ctx.sender().getUuid() == null) ? null : ctx.sender().getUuid().toString();
                if (uuid == null || uuid.isBlank()) {
                    ctx.sendMessage(MSG_PLAYERS_ONLY);
                    return;
                }
                if (shopUiService != null) {
                    shopUiService.requestOpen(uuid);
                }
                ctx.sendMessage(Message.raw("[RealmRuler] Opening CTF shop..."));
                return;
            }

            ctx.sendMessage(MSG_USAGE);
            return;
        }

        if (isNpc(sub)) {
            handleNpcCommand(ctx, args);
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

    private static boolean isNpc(String s) {
        if (s == null) return false;
        return "npc".equalsIgnoreCase(s) || "npcs".equalsIgnoreCase(s);
    }

    private static String safeTeamName(CtfMatchService.JoinLobbyResult jr) {
        if (jr == null || jr.team() == null) return "<unknown>";
        return jr.team().displayName();
    }

    private void handleNpcCommand(CommandContext ctx, String[] args) {
        if (npcArenaRepository == null || npcTestService == null || targetingService == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }
        if (ctx.sender() == null || !ctx.sender().hasPermission("realmruler.npc.manage")) {
            ctx.sendMessage(MSG_NO_NPC_PERMISSION);
            return;
        }
        if (args.length < 3) {
            ctx.sendMessage(MSG_NPC_USAGE);
            return;
        }

        String action = args[2];
        if ("arena".equalsIgnoreCase(action)) {
            handleNpcArenaCommand(ctx, args);
            return;
        }

        if ("spawn".equalsIgnoreCase(action)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_NPC_USAGE);
                return;
            }

            String arenaId = NpcArenaRepository.normalizeId(args[3]);
            String npcName = NpcTestService.normalizeNpcName(args[4]);
            if (arenaId == null || npcName == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId or npcName. Allowed: [a-z0-9_-]"));
                return;
            }

            NpcArenaRepository.ArenaDefinition arena = npcArenaRepository.getArena(arenaId);
            if (arena == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Arena not found: " + arenaId));
                return;
            }
            if (!arena.hasBounds()) {
                ctx.sendMessage(Message.raw("[RealmRuler] Arena bounds are not set. Run /rr npc arena pos1 " + arenaId + " and pos2."));
                return;
            }

            String senderUuid = senderUuid(ctx);
            if (senderUuid == null) {
                ctx.sendMessage(MSG_PLAYERS_ONLY);
                return;
            }

            TargetingService.PlayerLocationSnapshot snapshot = snapshotForSender(ctx);
            if (snapshot == null || !snapshot.isValid()) {
                ctx.sendMessage(Message.raw("[RealmRuler] Could not resolve your current position. Try moving and run again."));
                return;
            }
            if (!arena.worldName().equals(snapshot.worldName())) {
                ctx.sendMessage(Message.raw("[RealmRuler] You must be in arena world: " + arena.worldName()));
                return;
            }
            if (!arena.contains(snapshot.worldName(), snapshot.x(), snapshot.y(), snapshot.z())) {
                ctx.sendMessage(Message.raw("[RealmRuler] You must stand inside the arena bounds to spawn NPCs."));
                return;
            }

            NpcTestService.ServiceResult result = npcTestService.spawn(
                    arenaId,
                    npcName,
                    senderUuid,
                    new NpcTestService.SpawnTransform(
                            snapshot.worldName(),
                            snapshot.x(),
                            snapshot.y(),
                            snapshot.z(),
                            snapshot.pitch(),
                            snapshot.yaw(),
                            snapshot.roll()
                    )
            );
            ctx.sendMessage(Message.raw("[RealmRuler] " + result.message()));
            return;
        }

        if ("despawn".equalsIgnoreCase(action)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_NPC_USAGE);
                return;
            }

            String arenaId = NpcArenaRepository.normalizeId(args[3]);
            String npcName = NpcTestService.normalizeNpcName(args[4]);
            if (arenaId == null || npcName == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId or npcName. Allowed: [a-z0-9_-]"));
                return;
            }

            NpcTestService.ServiceResult result = npcTestService.despawn(arenaId, npcName);
            ctx.sendMessage(Message.raw("[RealmRuler] " + result.message()));
            return;
        }

        if ("clear".equalsIgnoreCase(action)) {
            if (args.length >= 4) {
                String arenaId = NpcArenaRepository.normalizeId(args[3]);
                if (arenaId == null) {
                    ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId. Allowed: [a-z0-9_-]"));
                    return;
                }
                int removed = npcTestService.clearArena(arenaId);
                ctx.sendMessage(Message.raw("[RealmRuler] Cleared " + removed + " NPC(s) in arena '" + arenaId + "'."));
                return;
            }

            int removed = npcTestService.clearAll();
            ctx.sendMessage(Message.raw("[RealmRuler] Cleared " + removed + " NPC(s) across all arenas."));
            return;
        }

        ctx.sendMessage(MSG_NPC_USAGE);
    }

    private void handleNpcArenaCommand(CommandContext ctx, String[] args) {
        if (args.length < 4) {
            ctx.sendMessage(MSG_NPC_USAGE);
            return;
        }

        String arenaAction = args[3];
        if ("list".equalsIgnoreCase(arenaAction)) {
            List<NpcArenaRepository.ArenaDefinition> arenas = npcArenaRepository.listArenas();
            if (arenas.isEmpty()) {
                ctx.sendMessage(Message.raw("[RealmRuler] No NPC arenas configured."));
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[RealmRuler] NPC arenas: ");
            for (int i = 0; i < arenas.size(); i++) {
                NpcArenaRepository.ArenaDefinition arena = arenas.get(i);
                if (i > 0) sb.append(" | ");
                sb.append(arena.arenaId())
                        .append(" (world=").append(arena.worldName())
                        .append(", bounds=").append(arena.hasBounds() ? "yes" : "no")
                        .append(", npcs=").append(npcTestService.trackedCountForArena(arena.arenaId()))
                        .append(")");
            }
            ctx.sendMessage(Message.raw(sb.toString()));
            return;
        }

        if ("create".equalsIgnoreCase(arenaAction)) {
            if (args.length < 6) {
                ctx.sendMessage(MSG_NPC_USAGE);
                return;
            }

            String arenaId = NpcArenaRepository.normalizeId(args[4]);
            if (arenaId == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId. Allowed: [a-z0-9_-]"));
                return;
            }

            World world = Universe.get().getWorld(args[5]);
            if (world == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] World not found: " + args[5]));
                return;
            }

            boolean created = npcArenaRepository.createArena(arenaId, world.getName());
            ctx.sendMessage(created
                    ? Message.raw("[RealmRuler] Created NPC arena '" + arenaId + "' in world '" + world.getName() + "'.")
                    : Message.raw("[RealmRuler] Arena already exists: " + arenaId));
            return;
        }

        if ("delete".equalsIgnoreCase(arenaAction)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_NPC_USAGE);
                return;
            }

            String arenaId = NpcArenaRepository.normalizeId(args[4]);
            if (arenaId == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId. Allowed: [a-z0-9_-]"));
                return;
            }

            int cleared = npcTestService.clearArena(arenaId);
            boolean deleted = npcArenaRepository.deleteArena(arenaId);
            if (!deleted) {
                ctx.sendMessage(Message.raw("[RealmRuler] Arena not found: " + arenaId));
                return;
            }
            ctx.sendMessage(Message.raw("[RealmRuler] Deleted arena '" + arenaId + "' (cleared " + cleared + " NPCs)."));
            return;
        }

        if ("info".equalsIgnoreCase(arenaAction)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_NPC_USAGE);
                return;
            }

            String arenaId = NpcArenaRepository.normalizeId(args[4]);
            if (arenaId == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId. Allowed: [a-z0-9_-]"));
                return;
            }

            NpcArenaRepository.ArenaDefinition arena = npcArenaRepository.getArena(arenaId);
            if (arena == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Arena not found: " + arenaId));
                return;
            }

            String pos1 = formatPos(arena.pos1());
            String pos2 = formatPos(arena.pos2());
            int tracked = npcTestService.trackedCountForArena(arena.arenaId());
            ctx.sendMessage(Message.raw("[RealmRuler] Arena '" + arena.arenaId()
                    + "': world=" + arena.worldName()
                    + ", enabled=" + arena.enabled()
                    + ", pos1=" + pos1
                    + ", pos2=" + pos2
                    + ", npcs=" + tracked));
            return;
        }

        if ("pos1".equalsIgnoreCase(arenaAction) || "pos2".equalsIgnoreCase(arenaAction)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_NPC_USAGE);
                return;
            }

            String arenaId = NpcArenaRepository.normalizeId(args[4]);
            if (arenaId == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Invalid arenaId. Allowed: [a-z0-9_-]"));
                return;
            }

            NpcArenaRepository.ArenaDefinition arena = npcArenaRepository.getArena(arenaId);
            if (arena == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Arena not found: " + arenaId));
                return;
            }

            TargetingService.PlayerLocationSnapshot snapshot = snapshotForSender(ctx);
            if (snapshot == null || !snapshot.isValid()) {
                ctx.sendMessage(Message.raw("[RealmRuler] Could not resolve your current position. Try moving and run again."));
                return;
            }
            if (!arena.worldName().equals(snapshot.worldName())) {
                ctx.sendMessage(Message.raw("[RealmRuler] You must be in arena world: " + arena.worldName()));
                return;
            }

            NpcArenaRepository.BlockPos blockPos = new NpcArenaRepository.BlockPos(
                    (int) Math.floor(snapshot.x()),
                    (int) Math.floor(snapshot.y()),
                    (int) Math.floor(snapshot.z())
            );

            boolean updated = "pos1".equalsIgnoreCase(arenaAction)
                    ? npcArenaRepository.setPos1(arenaId, blockPos)
                    : npcArenaRepository.setPos2(arenaId, blockPos);
            if (!updated) {
                ctx.sendMessage(Message.raw("[RealmRuler] Failed to update arena position."));
                return;
            }

            ctx.sendMessage(Message.raw("[RealmRuler] Set " + arenaAction.toLowerCase() + " for arena '" + arenaId
                    + "' to " + blockPos.x() + " " + blockPos.y() + " " + blockPos.z()));
            return;
        }

        ctx.sendMessage(MSG_NPC_USAGE);
    }

    private TargetingService.PlayerLocationSnapshot snapshotForSender(CommandContext ctx) {
        String uuid = senderUuid(ctx);
        if (uuid == null) return null;
        return targetingService.getLatestPlayerLocation(uuid);
    }

    private static String senderUuid(CommandContext ctx) {
        if (ctx == null || ctx.sender() == null || ctx.sender().getUuid() == null) return null;
        String uuid = ctx.sender().getUuid().toString();
        if (uuid == null || uuid.isBlank()) return null;
        return uuid;
    }

    private static String formatPos(NpcArenaRepository.BlockPos pos) {
        if (pos == null) return "<unset>";
        return pos.x() + "," + pos.y() + "," + pos.z();
    }

    private void handleStandCommand(CommandContext ctx, String[] args) {
        if (standRegistry == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }

        if (ctx.sender() == null || !ctx.sender().hasPermission("realmruler.ctf.stand.manage")) {
            ctx.sendMessage(MSG_NO_STAND_PERMISSION);
            return;
        }

        if (args.length < 4) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String standAction = args[3];

        if ("list".equalsIgnoreCase(standAction)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_USAGE);
                return;
            }

            CtfMatchService.Team team = CtfMatchService.parseTeam(args[4]);
            if (team == null) {
                ctx.sendMessage(MSG_USAGE);
                return;
            }

            List<CtfStandRegistryRepository.StandLocation> stands = standRegistry.getOrderedStands(team);
            if (stands.isEmpty()) {
                ctx.sendMessage(Message.raw("[RealmRuler] No registered stands for " + team.displayName() + "."));
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[RealmRuler] ").append(team.displayName()).append(" stands: ");
            for (int index = 0; index < stands.size(); index++) {
                CtfStandRegistryRepository.StandLocation stand = stands.get(index);
                if (index > 0) sb.append(" | ");
                if (index == 0) sb.append("Primary ");
                else sb.append("Fallback ").append(index).append(" ");
                sb.append(stand.worldName()).append(" ").append(stand.x()).append(" ").append(stand.y()).append(" ").append(stand.z());
            }
            ctx.sendMessage(Message.raw(sb.toString()));
            return;
        }

        if (!"add".equalsIgnoreCase(standAction)
                && !"remove".equalsIgnoreCase(standAction)
                && !"primary".equalsIgnoreCase(standAction)) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        if (args.length < 9) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        CtfMatchService.Team team = CtfMatchService.parseTeam(args[4]);
        if (team == null) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String worldName = args[5];
        Integer x = parseInt(args[6]);
        Integer y = parseInt(args[7]);
        Integer z = parseInt(args[8]);
        if (x == null || y == null || z == null) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            ctx.sendMessage(Message.raw("[RealmRuler] World not found: " + worldName));
            return;
        }

        if (simpleClaims != null && simpleClaims.isAvailable()) {
            int chunkX = ChunkUtil.chunkCoordinate(x);
            int chunkZ = ChunkUtil.chunkCoordinate(z);
            String ownerTeam = simpleClaims.getTeamForChunk(worldName, chunkX, chunkZ);
            if (ownerTeam == null || !ownerTeam.equalsIgnoreCase(team.displayName())) {
                ctx.sendMessage(Message.raw("[RealmRuler] Stand location must be inside " + team.displayName() + " team-claimed chunks."));
                return;
            }
        }

        CtfStandRegistryRepository.StandLocation stand = new CtfStandRegistryRepository.StandLocation(worldName, x, y, z);
        if ("add".equalsIgnoreCase(standAction)) {
            boolean added = standRegistry.addStand(team, stand);
            ctx.sendMessage(added
                    ? Message.raw("[RealmRuler] Added stand for " + team.displayName() + ": " + worldName + " " + x + " " + y + " " + z)
                    : Message.raw("[RealmRuler] Stand already registered for " + team.displayName() + "."));
            return;
        }

        if ("remove".equalsIgnoreCase(standAction)) {
            boolean removed = standRegistry.removeStand(team, stand);
            ctx.sendMessage(removed
                    ? Message.raw("[RealmRuler] Removed stand for " + team.displayName() + ": " + worldName + " " + x + " " + y + " " + z)
                    : Message.raw("[RealmRuler] Stand not found for " + team.displayName() + "."));
            return;
        }

        boolean primarySet = standRegistry.setPrimaryStand(team, stand);
        ctx.sendMessage(primarySet
                ? Message.raw("[RealmRuler] Updated primary stand for " + team.displayName() + ".")
                : Message.raw("[RealmRuler] Stand not found; add it first before setting primary."));
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
