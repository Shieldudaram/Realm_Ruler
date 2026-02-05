package com.Chris__.realm_ruler.match;

import com.Chris__.realm_ruler.core.LobbyHudState;
import com.Chris__.realm_ruler.modes.CtfMode;
import com.Chris__.realm_ruler.targeting.TargetingService;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Coordinates the high-level lifecycle of a Capture The Flag "match".
 *
 * For now this is intentionally minimal:
 * - resets CTF runtime state
 * - starts the shared match timer
 *
 * Later this becomes the home for:
 * - score / win conditions
 * - round reset behavior
 * - match start/stop announcements
 */
public final class CtfMatchService {

    public enum Team {
        BLUE("Blue"),
        RED("Red"),
        YELLOW("Yellow"),
        WHITE("White");

        private final String displayName;

        Team(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum JoinStatus {
        JOINED,
        ALREADY_WAITING,
        MATCH_RUNNING,
        NOT_READY
    }

    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        NOT_READY
    }

    private static final int DEFAULT_SECONDS = 15 * 60;

    private final TargetingService targetingService;
    private final CtfMode ctfMode;

    // Lobby state (in-memory only)
    private final Map<String, Team> lobbyTeamByUuid = new ConcurrentHashMap<>();
    private final Map<String, Team> matchTeamByUuid = new ConcurrentHashMap<>();
    private final Set<String> waitingUuids = ConcurrentHashMap.newKeySet();

    public CtfMatchService(TargetingService targetingService, CtfMode ctfMode) {
        this.targetingService = targetingService;
        this.ctfMode = ctfMode;
    }

    public record JoinLobbyResult(JoinStatus status, Team team, int waitingCount) {
    }

    public JoinLobbyResult joinLobby(String uuid) {
        return joinLobby(uuid, null);
    }

    public JoinLobbyResult joinLobby(String uuid, Team requestedTeam) {
        if (uuid == null || uuid.isBlank() || targetingService == null) {
            return new JoinLobbyResult(JoinStatus.NOT_READY, null, 0);
        }

        // Don't add to lobby while a match is running; but keep/show their team.
        if (targetingService.isMatchTimerRunning()) {
            Team team = matchTeamByUuid.get(uuid);
            return new JoinLobbyResult(JoinStatus.MATCH_RUNNING, team, waitingUuids.size());
        }

        Team team = (requestedTeam != null) ? requestedTeam : lobbyTeamByUuid.computeIfAbsent(uuid, k -> randomTeam());
        lobbyTeamByUuid.put(uuid, team);

        boolean added = waitingUuids.add(uuid);
        JoinStatus status = added ? JoinStatus.JOINED : JoinStatus.ALREADY_WAITING;
        return new JoinLobbyResult(status, team, waitingUuids.size());
    }

    public Team lobbyTeamFor(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        return lobbyTeamByUuid.get(uuid);
    }

    public Team activeMatchTeamFor(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        return matchTeamByUuid.get(uuid);
    }

    public boolean isActiveMatchParticipant(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        return matchTeamByUuid.containsKey(uuid);
    }

    public boolean leaveLobby(String uuid) {
        if (uuid == null || uuid.isBlank() || targetingService == null) return false;
        if (targetingService.isMatchTimerRunning()) return false;
        boolean removed = waitingUuids.remove(uuid);
        lobbyTeamByUuid.remove(uuid);
        return removed;
    }

    public LobbyHudState lobbyHudStateFor(String uuid) {
        if (uuid == null || uuid.isBlank()) return new LobbyHudState(false, "", 0);

        // While the match is running, we currently hide the lobby HUD to avoid custom HUD command issues
        // when multiple UI documents are appended at once. We can show team info again later once stable.
        if (isRunning()) return new LobbyHudState(false, "", 0);

        if (!waitingUuids.contains(uuid)) return new LobbyHudState(false, "", 0);

        Team team = lobbyTeamByUuid.get(uuid);
        if (team == null) return new LobbyHudState(false, "", 0);
        return new LobbyHudState(true, team.displayName(), waitingUuids.size());
    }

    public StartResult startCaptureTheFlag() {
        return startCaptureTheFlag(DEFAULT_SECONDS);
    }

    public StartResult startCaptureTheFlag(int seconds) {
        if (targetingService == null || ctfMode == null) return StartResult.NOT_READY;
        if (targetingService.isMatchTimerRunning()) return StartResult.ALREADY_RUNNING;

        ctfMode.resetMatch();

        // Move lobby assignments into the active match, then clear lobby so teams reroll next match.
        matchTeamByUuid.clear();
        for (String uuid : waitingUuids) {
            Team team = lobbyTeamByUuid.get(uuid);
            if (team == null) team = randomTeam();
            matchTeamByUuid.put(uuid, team);
        }
        waitingUuids.clear(); // clear lobby when match starts
        lobbyTeamByUuid.clear(); // forces reroll on next match

        targetingService.queueTimerStart(Math.max(0, seconds));
        return StartResult.STARTED;
    }

    public boolean stopCaptureTheFlag() {
        if (targetingService == null) return false;
        if (!targetingService.isMatchTimerRunning()) return false;
        targetingService.queueTimerStop();
        return true;
    }

    public void endMatch() {
        matchTeamByUuid.clear();
    }

    public Map<String, Team> getActiveMatchTeams() {
        return new HashMap<>(matchTeamByUuid);
    }

    public Map<String, Team> getLobbyWaitingTeamsSnapshot() {
        Map<String, Team> snapshot = new HashMap<>();
        for (String uuid : waitingUuids) {
            Team team = lobbyTeamByUuid.get(uuid);
            if (team != null) {
                snapshot.put(uuid, team);
            }
        }
        return snapshot;
    }

    public Set<String> getActiveMatchUuids() {
        return new HashSet<>(matchTeamByUuid.keySet());
    }

    public boolean isRunning() {
        return targetingService != null && targetingService.isMatchTimerRunning();
    }

    public int getRemainingSeconds() {
        return (targetingService == null) ? 0 : targetingService.getMatchTimerRemainingSeconds();
    }

    private static Team randomTeam() {
        Team[] all = Team.values();
        return all[ThreadLocalRandom.current().nextInt(all.length)];
    }

    public static Team parseTeam(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "red" -> Team.RED;
            case "blue" -> Team.BLUE;
            case "yellow" -> Team.YELLOW;
            case "white" -> Team.WHITE;
            default -> null;
        };
    }
}
