package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.targeting.TargetingService;
import com.Chris__.realm_ruler.world.StandSwapService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CtfMatchEndService {

    public enum EndReason {
        TIME_EXPIRED,
        STOPPED
    }

    private static final int WIN_POINTS_AWARD = 100;

    private final CtfMatchService matchService;
    private final SimpleClaimsCtfBridge simpleClaims;
    private final CtfFlagStateService flagStateService;
    private final CtfPointsRepository pointsRepository;
    private final StandSwapService standSwapService;
    private final TargetingService targetingService;
    private final Map<String, Player> playerByUuid;
    private final HytaleLogger logger;

    public CtfMatchEndService(CtfMatchService matchService,
                              SimpleClaimsCtfBridge simpleClaims,
                              CtfFlagStateService flagStateService,
                              CtfPointsRepository pointsRepository,
                              StandSwapService standSwapService,
                              TargetingService targetingService,
                              Map<String, Player> playerByUuid,
                              HytaleLogger logger) {
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.flagStateService = flagStateService;
        this.pointsRepository = pointsRepository;
        this.standSwapService = standSwapService;
        this.targetingService = targetingService;
        this.playerByUuid = playerByUuid;
        this.logger = logger;
    }

    public void endMatch(EndReason reason) {
        if (matchService == null) return;

        Set<String> matchUuids = matchService.getActiveMatchUuids();
        if (matchUuids.isEmpty()) return;

        if (flagStateService == null) return;

        Map<String, Integer> scores = flagStateService.computeScoresAtEnd();
        int red = scores.getOrDefault(CtfMatchService.Team.RED.displayName(), 0);
        int blue = scores.getOrDefault(CtfMatchService.Team.BLUE.displayName(), 0);
        int yellow = scores.getOrDefault(CtfMatchService.Team.YELLOW.displayName(), 0);
        int white = scores.getOrDefault(CtfMatchService.Team.WHITE.displayName(), 0);

        Universe.get().sendMessage(Message.raw("[RealmRuler] CTF ended! " +
                "Red: " + red + " | " +
                "Blue: " + blue + " | " +
                "Yellow: " + yellow + " | " +
                "White: " + white));

        CtfMatchService.Team winner = resolveWinner(red, blue, yellow, white);
        if (winner == null) {
            Universe.get().sendMessage(Message.raw("[RealmRuler] It's a draw!"));
        } else {
            Universe.get().sendMessage(Message.raw("[RealmRuler] Winner: " + winner.displayName() + "!"));

            if (pointsRepository != null) {
                for (Map.Entry<String, CtfMatchService.Team> e : matchService.getActiveMatchTeams().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    if (e.getValue() != winner) continue;
                    pointsRepository.addPoints(e.getKey(), WIN_POINTS_AWARD);
                }
            }
        }

        // Cleanup: remove flags from players + reset captured stands.
        flagStateService.cleanupAfterMatch(standSwapService, playerByUuid, matchUuids);
        boolean flagsReset = flagStateService.forceReturnAllFlagsToTeamStands(standSwapService);
        if (!flagsReset) {
            logger.atWarning().log("[RR-CTF] Match ended but one or more flags failed to reset to team stands.");
        }

        // Clear SimpleClaims temporary team access.
        if (simpleClaims != null && simpleClaims.isAvailable()) {
            simpleClaims.clearTeams(matchUuids);
        }

        // Return participants to their pre-match locations (or world spawn fallback).
        queuePostMatchRestores(matchUuids);

        // Clear match roster.
        matchService.endMatch();

        // Reset flag state for next match.
        flagStateService.resetForNewMatch();

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] Match ended. reason=%s winner=%s", reason, (winner == null) ? "<draw>" : winner.displayName());
        }
    }

    private void queuePostMatchRestores(Set<String> matchUuids) {
        if (matchService == null || matchUuids == null || matchUuids.isEmpty()) return;

        Map<String, CtfMatchService.PreMatchLocation> savedByUuid = matchService.consumePreMatchLocationsFor(matchUuids);
        if (targetingService == null) {
            logger.atWarning().log("[RR-CTF] targetingService missing; cannot restore post-match player positions.");
            return;
        }

        for (String uuid : matchUuids) {
            if (uuid == null || uuid.isBlank()) continue;

            CtfMatchService.PreMatchLocation saved = savedByUuid.get(uuid);
            CtfMatchService.PreMatchLocation target = null;

            if (saved != null && saved.isValid() && isWorldAvailable(saved.worldName())) {
                target = saved;
            } else {
                String preferredWorldName = (saved == null) ? null : saved.worldName();
                target = resolveWorldSpawnFallback(uuid, preferredWorldName);
            }

            if (target == null || !target.isValid()) {
                logger.atWarning().log("[RR-CTF] Failed to resolve post-match restore location. uuid=%s", uuid);
                continue;
            }

            targetingService.queueTeleport(
                    uuid,
                    target.worldName(),
                    target.x(),
                    target.y(),
                    target.z(),
                    target.pitch(),
                    target.yaw(),
                    target.roll()
            );
        }
    }

    private boolean isWorldAvailable(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;
        Universe universe = Universe.get();
        if (universe == null) return false;
        return universe.getWorlds().get(worldName) != null;
    }

    private CtfMatchService.PreMatchLocation resolveWorldSpawnFallback(String uuid, String preferredWorldName) {
        Universe universe = Universe.get();
        if (universe == null) return null;

        World world = null;
        if (preferredWorldName != null && !preferredWorldName.isBlank()) {
            world = universe.getWorlds().get(preferredWorldName);
        }
        if (world == null) {
            world = universe.getDefaultWorld();
        }
        if (world == null) return null;

        try {
            UUID playerUuid = parseUuid(uuid);
            if (playerUuid == null) {
                playerUuid = UUID.randomUUID();
            }

            Transform spawn = null;
            if (world.getWorldConfig() != null && world.getWorldConfig().getSpawnProvider() != null) {
                spawn = world.getWorldConfig().getSpawnProvider().getSpawnPoint(world, playerUuid);
            }
            if (spawn == null || spawn.getPosition() == null) {
                return null;
            }

            Vector3d pos = spawn.getPosition();
            Vector3f rot = spawn.getRotation();
            float pitch = 0f;
            float yaw = 0f;
            float roll = 0f;
            if (rot != null) {
                pitch = rot.getPitch();
                yaw = rot.getYaw();
                roll = rot.getRoll();
            }

            CtfMatchService.PreMatchLocation fallback = new CtfMatchService.PreMatchLocation(
                    world.getName(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    pitch,
                    yaw,
                    roll
            );
            return fallback.isValid() ? fallback : null;
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to resolve world spawn fallback. uuid=%s world=%s", uuid, preferredWorldName);
            return null;
        }
    }

    private static UUID parseUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return UUID.fromString(uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CtfMatchService.Team resolveWinner(int red, int blue, int yellow, int white) {
        int max = Math.max(Math.max(red, blue), Math.max(yellow, white));
        CtfMatchService.Team winner = null;
        int countMax = 0;

        if (red == max) {
            winner = CtfMatchService.Team.RED;
            countMax++;
        }
        if (blue == max) {
            winner = CtfMatchService.Team.BLUE;
            countMax++;
        }
        if (yellow == max) {
            winner = CtfMatchService.Team.YELLOW;
            countMax++;
        }
        if (white == max) {
            winner = CtfMatchService.Team.WHITE;
            countMax++;
        }

        return (countMax == 1) ? winner : null;
    }
}
