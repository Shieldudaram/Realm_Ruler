package com.Chris__.realm_ruler.match;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CtfStandRegistryRepository {

    private static final String FILE_NAME = "ctf_stands.json";
    private static final int FILE_VERSION = 1;

    public record StandLocation(String worldName, int x, int y, int z) {
        public boolean isValid() {
            return worldName != null && !worldName.isBlank();
        }

        public boolean sameAs(StandLocation other) {
            if (other == null) return false;
            return worldName.equals(other.worldName) && x == other.x && y == other.y && z == other.z;
        }
    }

    private static final class FileModel {
        int version = FILE_VERSION;
        Map<String, List<StandLocation>> teams = new java.util.LinkedHashMap<>();
    }

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private final Map<CtfMatchService.Team, List<StandLocation>> standsByTeam =
            new EnumMap<>(CtfMatchService.Team.class);

    public CtfStandRegistryRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        for (CtfMatchService.Team team : CtfMatchService.Team.values()) {
            standsByTeam.put(team, new ArrayList<>());
        }
        load();
    }

    public List<StandLocation> getOrderedStands(CtfMatchService.Team team) {
        if (team == null) return List.of();
        synchronized (lock) {
            return List.copyOf(standsByTeam.getOrDefault(team, List.of()));
        }
    }

    public StandLocation getPrimaryStand(CtfMatchService.Team team) {
        if (team == null) return null;
        synchronized (lock) {
            List<StandLocation> list = standsByTeam.get(team);
            if (list == null || list.isEmpty()) return null;
            return list.getFirst();
        }
    }

    public boolean addStand(CtfMatchService.Team team, StandLocation stand) {
        if (team == null || stand == null || !stand.isValid()) return false;
        synchronized (lock) {
            List<StandLocation> list = standsByTeam.computeIfAbsent(team, ignored -> new ArrayList<>());
            for (StandLocation existing : list) {
                if (existing.sameAs(stand)) return false;
            }
            list.add(stand);
            saveLocked();
            return true;
        }
    }

    public boolean removeStand(CtfMatchService.Team team, StandLocation stand) {
        if (team == null || stand == null || !stand.isValid()) return false;
        synchronized (lock) {
            List<StandLocation> list = standsByTeam.get(team);
            if (list == null || list.isEmpty()) return false;

            boolean removed = list.removeIf(existing -> existing.sameAs(stand));
            if (!removed) return false;

            saveLocked();
            return true;
        }
    }

    public boolean setPrimaryStand(CtfMatchService.Team team, StandLocation stand) {
        if (team == null || stand == null || !stand.isValid()) return false;
        synchronized (lock) {
            List<StandLocation> list = standsByTeam.get(team);
            if (list == null || list.isEmpty()) return false;

            int foundIndex = -1;
            for (int index = 0; index < list.size(); index++) {
                if (list.get(index).sameAs(stand)) {
                    foundIndex = index;
                    break;
                }
            }

            if (foundIndex < 0) return false;
            if (foundIndex == 0) return true;

            StandLocation chosen = list.remove(foundIndex);
            list.addFirst(chosen);
            saveLocked();
            return true;
        }
    }

    private void load() {
        if (filePath == null) return;
        synchronized (lock) {
            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    saveLocked();
                    return;
                }

                try (Reader reader = Files.newBufferedReader(filePath)) {
                    FileModel model = gson.fromJson(reader, FileModel.class);
                    if (model == null || model.teams == null) {
                        return;
                    }

                    for (Map.Entry<String, List<StandLocation>> entry : model.teams.entrySet()) {
                        CtfMatchService.Team team = parseTeam(entry.getKey());
                        if (team == null) continue;

                        List<StandLocation> dst = standsByTeam.computeIfAbsent(team, ignored -> new ArrayList<>());
                        dst.clear();

                        List<StandLocation> src = entry.getValue();
                        if (src == null) continue;

                        for (StandLocation stand : src) {
                            if (stand == null || !stand.isValid()) continue;
                            if (contains(dst, stand)) continue;
                            dst.add(stand);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[RR-CTF] Failed to load stand registry.");
            }
        }
    }

    private void saveLocked() {
        if (filePath == null) return;
        try {
            Files.createDirectories(filePath.getParent());
            FileModel out = new FileModel();
            for (CtfMatchService.Team team : CtfMatchService.Team.values()) {
                List<StandLocation> stands = standsByTeam.get(team);
                out.teams.put(team.name().toLowerCase(Locale.ROOT), (stands == null) ? List.of() : new ArrayList<>(stands));
            }

            try (Writer writer = Files.newBufferedWriter(filePath)) {
                gson.toJson(out, writer);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to save stand registry.");
        }
    }

    private static boolean contains(List<StandLocation> list, StandLocation candidate) {
        for (StandLocation location : list) {
            if (location.sameAs(candidate)) return true;
        }
        return false;
    }

    private static CtfMatchService.Team parseTeam(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "red" -> CtfMatchService.Team.RED;
            case "blue" -> CtfMatchService.Team.BLUE;
            case "yellow" -> CtfMatchService.Team.YELLOW;
            case "white" -> CtfMatchService.Team.WHITE;
            default -> null;
        };
    }
}
