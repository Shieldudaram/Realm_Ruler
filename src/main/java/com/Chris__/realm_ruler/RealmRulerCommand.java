package com.Chris__.realm_ruler;

import com.Chris__.realm_ruler.ctf.CtfWorkflowFacade;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.match.CtfArmorLoadoutService;
import com.Chris__.realm_ruler.match.CtfBalloonSpawnService;
import com.Chris__.realm_ruler.match.CtfFlagStateService;
import com.Chris__.realm_ruler.match.CtfMatchService;
import com.Chris__.realm_ruler.match.CtfPointsRepository;
import com.Chris__.realm_ruler.match.CtfRegionRepository;
import com.Chris__.realm_ruler.match.CtfShopService;
import com.Chris__.realm_ruler.match.CtfStandRegistryRepository;
import com.Chris__.realm_ruler.modes.ctf.CtfRules;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RealmRulerCommand extends CommandBase {

    private static final Message MSG_USAGE =
            Message.raw("Usage: /rr ctf <join [random|red|blue|yellow|white]|leave|start [minutes]|stop|points|shop [list|info|buy|ui] ...|balloons <status|spawnnow [count]>|stand <add|remove|list|primary> ...|region <create|pos1|pos2|info|clear> ...> | /rr npc <arena|spawn|despawn|clear>");

    private static final Message MSG_NOT_READY =
            Message.raw("[RealmRuler] Not ready yet (plugin still starting?).");

    private static final Message MSG_PLAYERS_ONLY =
            Message.raw("[RealmRuler] Players only.");
    private static final Message MSG_NO_STAND_PERMISSION =
            Message.raw("[RealmRuler] Missing permission: realmruler.ctf.stand.manage");
    private static final Message MSG_NO_REGION_PERMISSION =
            Message.raw("[RealmRuler] Missing permission: realmruler.ctf.region.manage");
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
    private final CtfShopService shopService;
    private final CtfShopUiService shopUiService;
    private final CtfWorkflowFacade ctfWorkflow;
    private final CtfBalloonSpawnService balloonSpawnService;
    private final CtfRegionRepository regionRepository;
    private final CtfArmorLoadoutService armorLoadoutService;
    private final NpcArenaRepository npcArenaRepository;
    private final NpcTestService npcTestService;

    public RealmRulerCommand(CtfMatchService matchService,
                             SimpleClaimsCtfBridge simpleClaims,
                             TargetingService targetingService,
                             CtfFlagStateService flagStateService,
                             CtfStandRegistryRepository standRegistry,
                             CtfPointsRepository pointsRepository,
                             CtfShopService shopService,
                             CtfShopUiService shopUiService,
                             CtfWorkflowFacade ctfWorkflow,
                             CtfBalloonSpawnService balloonSpawnService,
                             CtfRegionRepository regionRepository,
                             CtfArmorLoadoutService armorLoadoutService,
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
        this.shopService = shopService;
        this.shopUiService = shopUiService;
        this.ctfWorkflow = ctfWorkflow;
        this.balloonSpawnService = balloonSpawnService;
        this.regionRepository = regionRepository;
        this.armorLoadoutService = armorLoadoutService;
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

            if (ctfWorkflow == null) {
                ctx.sendMessage(MSG_NOT_READY);
                return;
            }

            if ("join".equalsIgnoreCase(action)) {
                Player senderPlayer = ctx.senderAs(Player.class);
                String uuid = senderUuid(ctx);
                if (uuid == null || senderPlayer == null) {
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
                sendActionResult(ctx, ctfWorkflow.joinLobby(senderPlayer, uuid, requested));
                return;
            }

            if ("stand".equalsIgnoreCase(action)) {
                handleStandCommand(ctx, args);
                return;
            }

            if ("region".equalsIgnoreCase(action)) {
                handleRegionCommand(ctx, args);
                return;
            }

            if ("leave".equalsIgnoreCase(action)) {
                Player senderPlayer = ctx.senderAs(Player.class);
                String uuid = senderUuid(ctx);
                if (uuid == null || senderPlayer == null) {
                    ctx.sendMessage(MSG_PLAYERS_ONLY);
                    return;
                }
                sendActionResult(ctx, ctfWorkflow.leaveLobby(senderPlayer, uuid));
                return;
            }

            if ("start".equalsIgnoreCase(action)) {
                int minutes = CtfWorkflowFacade.DEFAULT_MATCH_MINUTES;
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
                sendActionResult(ctx, ctfWorkflow.startMatch(minutes));
                return;
            }

            if ("stop".equalsIgnoreCase(action)) {
                sendActionResult(ctx, ctfWorkflow.stopMatch());
                return;
            }

            if ("points".equalsIgnoreCase(action)) {
                String uuid = senderUuid(ctx);
                if (uuid == null || uuid.isBlank()) {
                    ctx.sendMessage(MSG_PLAYERS_ONLY);
                    return;
                }
                sendActionResult(ctx, ctfWorkflow.pointsStatus(uuid));
                return;
            }

            if ("shop".equalsIgnoreCase(action)) {
                handleShopCommand(ctx, args);
                return;
            }

            if ("balloons".equalsIgnoreCase(action)) {
                handleBalloonsCommand(ctx, args);
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

    private void handleShopCommand(CommandContext ctx, String[] args) {
        if (ctfWorkflow == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }

        String shopAction = (args.length >= 4) ? args[3] : "list";
        if (shopAction == null || shopAction.isBlank()) {
            shopAction = "list";
        }
        shopAction = shopAction.trim().toLowerCase(Locale.ROOT);

        if ("ui".equals(shopAction)) {
            String uuid = senderUuid(ctx);
            if (uuid == null) {
                ctx.sendMessage(MSG_PLAYERS_ONLY);
                return;
            }
            if (shopUiService == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] CTF shop UI is unavailable. Use /rr ctf shop list."));
                return;
            }
            if (!shopUiService.isUiAvailable()) {
                ctx.sendMessage(Message.raw("[RealmRuler] CTF shop UI is unavailable right now. Use /rr ctf shop list."));
                return;
            }
            shopUiService.requestOpen(uuid);
            ctx.sendMessage(Message.raw("[RealmRuler] Opening CTF shop UI..."));
            return;
        }

        if ("list".equals(shopAction)) {
            List<CtfShopService.ShopItemView> items = ctfWorkflow.listEnabledShopItems();
            if (items.isEmpty()) {
                ctx.sendMessage(Message.raw("[RealmRuler] No CTF shop items are configured."));
                return;
            }

            ctx.sendMessage(Message.raw("[RealmRuler] CTF shop items (" + items.size() + "):"));
            for (CtfShopService.ShopItemView item : items) {
                if (item == null) continue;
                String tags = formatShopTags(item);
                ctx.sendMessage(Message.raw(" - " + item.id() + " | " + item.name() + " | " + item.cost() + " pts" + tags));
            }
            return;
        }

        if ("info".equals(shopAction)) {
            if (args.length < 5) {
                ctx.sendMessage(Message.raw("[RealmRuler] Usage: /rr ctf shop info <id>"));
                return;
            }
            CtfShopService.ShopItemView item = ctfWorkflow.describeShopItem(args[4]);
            if (item == null) {
                ctx.sendMessage(Message.raw("[RealmRuler] Shop item not found: " + args[4]));
                return;
            }

            ctx.sendMessage(Message.raw("[RealmRuler] Shop item '" + item.id() + "': "
                    + item.name() + " | cost=" + item.cost() + " | availability=" + safeField(item.availability(), "any")
                    + " | teamRule=" + safeField(item.teamRule(), "any")
                    + " | team=" + safeField(item.team(), "any")
                    + " | rewards=" + CtfWorkflowFacade.formatRewards(item)));
            return;
        }

        if ("buy".equals(shopAction)) {
            if (args.length < 5) {
                ctx.sendMessage(Message.raw("[RealmRuler] Usage: /rr ctf shop buy <id>"));
                return;
            }

            Player senderPlayer = ctx.senderAs(Player.class);
            String uuid = senderUuid(ctx);
            if (senderPlayer == null || uuid == null) {
                ctx.sendMessage(MSG_PLAYERS_ONLY);
                return;
            }

            sendActionResult(ctx, ctfWorkflow.purchaseShopItem(senderPlayer, uuid, args[4]));
            return;
        }

        ctx.sendMessage(Message.raw("[RealmRuler] Usage: /rr ctf shop [list|info <id>|buy <id>|ui]"));
    }

    private void handleBalloonsCommand(CommandContext ctx, String[] args) {
        if (ctfWorkflow == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }

        if (args.length < 4) {
            ctx.sendMessage(Message.raw("[RealmRuler] Usage: /rr ctf balloons <status|spawnnow [count]>"));
            return;
        }

        String balloonsAction = args[3];
        if ("status".equalsIgnoreCase(balloonsAction)) {
            sendActionResult(ctx, ctfWorkflow.balloonStatus(ctx.sender() != null && ctx.sender().hasPermission(CtfWorkflowFacade.REGION_PERMISSION)));
            return;
        }

        if ("spawnnow".equalsIgnoreCase(balloonsAction)) {
            int count = 1;
            if (args.length >= 5) {
                Integer parsed = parseInt(args[4]);
                if (parsed == null || parsed <= 0) {
                    ctx.sendMessage(Message.raw("[RealmRuler] Invalid balloon count: " + args[4]));
                    return;
                }
                count = Math.min(parsed, 50);
            }

            sendActionResult(
                    ctx,
                    ctfWorkflow.spawnBalloons(
                            ctx.sender() != null && ctx.sender().hasPermission(CtfWorkflowFacade.REGION_PERMISSION),
                            senderUuid(ctx),
                            count
                    )
            );
            return;
        }

        ctx.sendMessage(Message.raw("[RealmRuler] Usage: /rr ctf balloons <status|spawnnow [count]>"));
    }

    private static String formatShopTags(CtfShopService.ShopItemView item) {
        return CtfWorkflowFacade.formatShopTags(item);
    }

    private static String safeField(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value;
    }

    private static void sendActionResult(CommandContext ctx, CtfWorkflowFacade.ActionResult result) {
        if (ctx == null || result == null) return;
        String message = result.message();
        if (message != null && !message.isBlank()) {
            ctx.sendMessage(Message.raw("[RealmRuler] " + message));
        }
        String detail = result.detailMessage();
        if (detail != null && !detail.isBlank()) {
            ctx.sendMessage(Message.raw("[RealmRuler] " + detail));
        }
    }

    private static String[] splitArgs(String input) {
        if (input == null) return new String[0];
        String s = input.trim();
        if (s.startsWith("/")) s = s.substring(1).trim();
        return s.isEmpty() ? new String[0] : s.split("\\s+");
    }

    private static boolean isCtf(String s) {
        if (s == null) return false;
        return "ctf".equalsIgnoreCase(s) || "capturetheflag".equalsIgnoreCase(s);
    }

    private static boolean isNpc(String s) {
        if (s == null) return false;
        return "npc".equalsIgnoreCase(s) || "npcs".equalsIgnoreCase(s);
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
        if (ctfWorkflow == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }

        boolean hasPermission = ctx.sender() != null && ctx.sender().hasPermission(CtfWorkflowFacade.STAND_PERMISSION);
        if (!hasPermission) {
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

            List<CtfStandRegistryRepository.StandLocation> stands = ctfWorkflow.listStands(team);
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

        if ("add".equalsIgnoreCase(standAction)) {
            sendActionResult(ctx, ctfWorkflow.addStand(hasPermission, team, worldName, x, y, z));
            return;
        }

        if ("remove".equalsIgnoreCase(standAction)) {
            sendActionResult(ctx, ctfWorkflow.removeStand(hasPermission, team, worldName, x, y, z));
            return;
        }

        sendActionResult(ctx, ctfWorkflow.setPrimaryStand(hasPermission, team, worldName, x, y, z));
    }

    private void handleRegionCommand(CommandContext ctx, String[] args) {
        if (ctfWorkflow == null) {
            ctx.sendMessage(MSG_NOT_READY);
            return;
        }

        boolean hasPermission = ctx.sender() != null && ctx.sender().hasPermission(CtfWorkflowFacade.REGION_PERMISSION);
        if (!hasPermission) {
            ctx.sendMessage(MSG_NO_REGION_PERMISSION);
            return;
        }

        if (args.length < 4) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String regionAction = args[3];
        if ("create".equalsIgnoreCase(regionAction)) {
            if (args.length < 5) {
                ctx.sendMessage(MSG_USAGE);
                return;
            }
            sendActionResult(ctx, ctfWorkflow.createRegion(hasPermission, args[4]));
            return;
        }

        if ("clear".equalsIgnoreCase(regionAction)) {
            sendActionResult(ctx, ctfWorkflow.clearRegion(hasPermission));
            return;
        }

        if ("info".equalsIgnoreCase(regionAction)) {
            CtfWorkflowFacade.RegionSnapshot snapshot = ctfWorkflow.snapshotRegion(hasPermission);
            if ("<unset>".equals(snapshot.worldName())) {
                ctx.sendMessage(Message.raw("[RealmRuler] No CTF region configured."));
                return;
            }
            ctx.sendMessage(Message.raw("[RealmRuler] CTF region: world=" + snapshot.worldName()
                    + ", enabled=" + snapshot.enabled()
                    + ", pos1=" + snapshot.pos1()
                    + ", pos2=" + snapshot.pos2()
                    + ", ready=" + snapshot.ready()));
            return;
        }

        if ("pos1".equalsIgnoreCase(regionAction) || "pos2".equalsIgnoreCase(regionAction)) {
            sendActionResult(ctx, ctfWorkflow.setRegionPosHere(hasPermission, senderUuid(ctx), "pos1".equalsIgnoreCase(regionAction)));
            return;
        }

        ctx.sendMessage(MSG_USAGE);
    }

    private static boolean containsObjectiveFlag(com.hypixel.hytale.server.core.entity.entities.Player player) {
        if (player == null) return false;
        Inventory inv = player.getInventory();
        if (inv == null) return false;

        return containsObjectiveFlag(inv.getHotbar())
                || containsObjectiveFlag(inv.getStorage())
                || containsObjectiveFlag(inv.getBackpack())
                || containsObjectiveFlag(inv.getTools())
                || containsObjectiveFlag(inv.getUtility())
                || containsObjectiveFlag(inv.getArmor());
    }

    private static boolean containsObjectiveFlag(ItemContainer container) {
        if (container == null) return false;
        final boolean[] found = new boolean[]{false};
        container.forEach((slot, stack) -> {
            if (found[0]) return;
            if (stack == null || stack.getItemId() == null) return;
            if (CtfRules.isCustomFlagId(stack.getItemId())) {
                found[0] = true;
            }
        });
        return found[0];
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
