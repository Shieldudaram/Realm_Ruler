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

    private static final int DEFAULT_SECONDS = 8 * 60;

    private final TargetingService targetingService;
    private final CtfMode ctfMode;

    // Lobby state (in-memory only)
    private final Map<String, Team> lobbyTeamByUuid = new ConcurrentHashMap<>();
    private final Map<String, Team> matchTeamByUuid = new ConcurrentHashMap<>();
    private final Map<String, PreMatchLocation> preMatchLocationByUuid = new ConcurrentHashMap<>();
    private final Set<String> waitingUuids = ConcurrentHashMap.newKeySet();
    private volatile boolean stopRequested = false;

    public CtfMatchService(TargetingService targetingService, CtfMode ctfMode) {
        this.targetingService = targetingService;
        this.ctfMode = ctfMode;
    }

    public record JoinLobbyResult(JoinStatus status, Team team, int waitingCount) {
    }

    public record PreMatchLocation(String worldName, double x, double y, double z,
                                   float pitch, float yaw, float roll) {
        public boolean isValid() {
            return worldName != null && !worldName.isBlank()
                    && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(pitch) && Float.isFinite(yaw) && Float.isFinite(roll);
        }
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
        if (uuid == null || uuid.isBlank()) return new LobbyHudState(false, "", 0, "");

        // While the match is running, we currently hide the lobby HUD to avoid custom HUD command issues
        // when multiple UI documents are appended at once. We can show team info again later once stable.
        if (isRunning()) return new LobbyHudState(false, "", 0, "");

        if (!waitingUuids.contains(uuid)) return new LobbyHudState(false, "", 0, "");

        Team team = lobbyTeamByUuid.get(uuid);
        if (team == null) return new LobbyHudState(false, "", 0, "");
        return new LobbyHudState(true, team.displayName(), waitingUuids.size(), waitingTeamCountsLine());
    }

    public StartResult startCaptureTheFlag() {
        return startCaptureTheFlag(DEFAULT_SECONDS);
    }

    public StartResult startCaptureTheFlag(int seconds) {
        if (targetingService == null || ctfMode == null) return StartResult.NOT_READY;
        if (targetingService.isMatchTimerRunning()) return StartResult.ALREADY_RUNNING;

        ctfMode.resetMatch();
        stopRequested = false;
        preMatchLocationByUuid.clear();

        // Move lobby assignments into the active match, then clear lobby so teams reroll next match.
        matchTeamByUuid.clear();
        for (String uuid : waitingUuids) {
            Team team = lobbyTeamByUuid.get(uuid);
            if (team == null) team = randomTeam();
            matchTeamByUuid.put(uuid, team);
        }

        // Snapshot where participants were right before the match starts.
        for (String uuid : matchTeamByUuid.keySet()) {
            if (uuid == null || uuid.isBlank()) continue;
            TargetingService.PlayerLocationSnapshot snapshot = targetingService.getLatestPlayerLocation(uuid);
            if (snapshot == null || !snapshot.isValid()) continue;
            preMatchLocationByUuid.put(uuid, new PreMatchLocation(
                    snapshot.worldName(),
                    snapshot.x(),
                    snapshot.y(),
                    snapshot.z(),
                    snapshot.pitch(),
                    snapshot.yaw(),
                    snapshot.roll()
            ));
        }

        waitingUuids.clear(); // clear lobby when match starts
        lobbyTeamByUuid.clear(); // forces reroll on next match

        targetingService.queueTimerStart(Math.max(0, seconds));
        return StartResult.STARTED;
    }

    public boolean stopCaptureTheFlag() {
        if (targetingService == null) return false;
        if (!targetingService.isMatchTimerRunning()) return false;
        stopRequested = true;
        targetingService.queueTimerStop();
        return true;
    }

    public void endMatch() {
        matchTeamByUuid.clear();
        preMatchLocationByUuid.clear();
    }

    public boolean consumeStopRequested() {
        boolean v = stopRequested;
        stopRequested = false;
        return v;
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

    public Map<String, PreMatchLocation> consumePreMatchLocationsFor(Set<String> uuids) {
        Map<String, PreMatchLocation> out = new HashMap<>();
        if (uuids == null || uuids.isEmpty()) return out;

        for (String uuid : uuids) {
            if (uuid == null || uuid.isBlank()) continue;
            PreMatchLocation loc = preMatchLocationByUuid.remove(uuid);
            if (loc != null) {
                out.put(uuid, loc);
            }
        }
        return out;
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

    public static Team parseTeamLoose(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw
                .replaceAll("§.", " ")
                .replaceAll("\\p{Cntrl}", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]+", " ")
                .trim();
        if (normalized.isEmpty()) return null;

        Team found = null;
        for (String token : normalized.split("\\s+")) {
            Team parsed = parseTeam(token);
            if (parsed == null) continue;
            if (found != null && found != parsed) {
                return null;
            }
            found = parsed;
        }
        return found;
    }

    public static String canonicalTeamDisplayName(String raw) {
        Team parsed = parseTeamLoose(raw);
        return (parsed == null) ? null : parsed.displayName();
    }

    private String waitingTeamCountsLine() {
        int red = 0;
        int blue = 0;
        int yellow = 0;
        int white = 0;

        for (String uuid : waitingUuids) {
            Team team = lobbyTeamByUuid.get(uuid);
            if (team == null) continue;
            switch (team) {
                case RED -> red++;
                case BLUE -> blue++;
                case YELLOW -> yellow++;
                case WHITE -> white++;
            }
        }

        return "Red:" + red
                + " Blue:" + blue
                + " Yellow:" + yellow
                + " White:" + white;
    }
}
