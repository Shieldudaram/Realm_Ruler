package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.RrDebugFlags;
import com.Chris__.realm_ruler.integration.SimpleClaimsCtfBridge;
import com.Chris__.realm_ruler.world.StandSwapService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.Map;
import java.util.Set;

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
    private final Map<String, Player> playerByUuid;
    private final HytaleLogger logger;

    public CtfMatchEndService(CtfMatchService matchService,
                              SimpleClaimsCtfBridge simpleClaims,
                              CtfFlagStateService flagStateService,
                              CtfPointsRepository pointsRepository,
                              StandSwapService standSwapService,
                              Map<String, Player> playerByUuid,
                              HytaleLogger logger) {
        this.matchService = matchService;
        this.simpleClaims = simpleClaims;
        this.flagStateService = flagStateService;
        this.pointsRepository = pointsRepository;
        this.standSwapService = standSwapService;
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

        // Clear SimpleClaims temporary team access.
        if (simpleClaims != null && simpleClaims.isAvailable()) {
            simpleClaims.clearTeams(matchUuids);
        }

        // Clear match roster.
        matchService.endMatch();

        // Reset flag state for next match.
        flagStateService.resetForNewMatch();

        if (RrDebugFlags.verbose()) {
            logger.atInfo().log("[RR-CTF] Match ended. reason=%s winner=%s", reason, (winner == null) ? "<draw>" : winner.displayName());
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
