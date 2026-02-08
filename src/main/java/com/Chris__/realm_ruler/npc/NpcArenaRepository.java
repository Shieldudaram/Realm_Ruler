package com.Chris__.realm_ruler.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class NpcArenaRepository {

    private static final String FILE_NAME = "npc_arenas.json";
    private static final int FILE_VERSION = 1;
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    public record BlockPos(int x, int y, int z) {
    }

    public record ArenaDefinition(String arenaId,
                                  String worldName,
                                  BlockPos pos1,
                                  BlockPos pos2,
                                  boolean enabled) {
        public boolean hasBounds() {
            return pos1 != null && pos2 != null;
        }

        public boolean contains(String currentWorld, double x, double y, double z) {
            if (!enabled) return false;
            if (worldName == null || worldName.isBlank()) return false;
            if (currentWorld == null || !worldName.equals(currentWorld)) return false;
            if (pos1 == null || pos2 == null) return false;

            int blockX = (int) Math.floor(x);
            int blockY = (int) Math.floor(y);
            int blockZ = (int) Math.floor(z);

            int minX = Math.min(pos1.x(), pos2.x());
            int maxX = Math.max(pos1.x(), pos2.x());
            int minY = Math.min(pos1.y(), pos2.y());
            int maxY = Math.max(pos1.y(), pos2.y());
            int minZ = Math.min(pos1.z(), pos2.z());
            int maxZ = Math.max(pos1.z(), pos2.z());

            return blockX >= minX && blockX <= maxX
                    && blockY >= minY && blockY <= maxY
                    && blockZ >= minZ && blockZ <= maxZ;
        }
    }

    private static final class FileModel {
        int version = FILE_VERSION;
        List<ArenaDefinition> arenas = new ArrayList<>();
    }

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;
    private final Map<String, ArenaDefinition> arenasById = new LinkedHashMap<>();

    public NpcArenaRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        load();
    }

    public static String normalizeId(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!ID_PATTERN.matcher(normalized).matches()) return null;
        return normalized;
    }

    public boolean createArena(String arenaId, String worldName) {
        String normalizedId = normalizeId(arenaId);
        if (normalizedId == null) return false;
        if (worldName == null || worldName.isBlank()) return false;

        synchronized (lock) {
            if (arenasById.containsKey(normalizedId)) return false;
            arenasById.put(normalizedId, new ArenaDefinition(normalizedId, worldName, null, null, true));
            saveLocked();
            return true;
        }
    }

    public boolean deleteArena(String arenaId) {
        String normalizedId = normalizeId(arenaId);
        if (normalizedId == null) return false;

        synchronized (lock) {
            ArenaDefinition removed = arenasById.remove(normalizedId);
            if (removed == null) return false;
            saveLocked();
            return true;
        }
    }

    public ArenaDefinition getArena(String arenaId) {
        String normalizedId = normalizeId(arenaId);
        if (normalizedId == null) return null;
        synchronized (lock) {
            return arenasById.get(normalizedId);
        }
    }

    public List<ArenaDefinition> listArenas() {
        synchronized (lock) {
            return List.copyOf(arenasById.values());
        }
    }

    public boolean setPos1(String arenaId, BlockPos pos) {
        return updatePos(arenaId, pos, true);
    }

    public boolean setPos2(String arenaId, BlockPos pos) {
        return updatePos(arenaId, pos, false);
    }

    public boolean setEnabled(String arenaId, boolean enabled) {
        String normalizedId = normalizeId(arenaId);
        if (normalizedId == null) return false;

        synchronized (lock) {
            ArenaDefinition arena = arenasById.get(normalizedId);
            if (arena == null) return false;

            arenasById.put(normalizedId, new ArenaDefinition(
                    arena.arenaId(),
                    arena.worldName(),
                    arena.pos1(),
                    arena.pos2(),
                    enabled
            ));
            saveLocked();
            return true;
        }
    }

    public boolean contains(String arenaId, String worldName, double x, double y, double z) {
        ArenaDefinition arena = getArena(arenaId);
        if (arena == null) return false;
        return arena.contains(worldName, x, y, z);
    }

    private boolean updatePos(String arenaId, BlockPos pos, boolean first) {
        String normalizedId = normalizeId(arenaId);
        if (normalizedId == null || pos == null) return false;

        synchronized (lock) {
            ArenaDefinition arena = arenasById.get(normalizedId);
            if (arena == null) return false;

            arenasById.put(normalizedId, new ArenaDefinition(
                    arena.arenaId(),
                    arena.worldName(),
                    first ? pos : arena.pos1(),
                    first ? arena.pos2() : pos,
                    arena.enabled()
            ));
            saveLocked();
            return true;
        }
    }

    private void load() {
        if (filePath == null) return;
        synchronized (lock) {
            arenasById.clear();
            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    saveLocked();
                    return;
                }

                try (Reader reader = Files.newBufferedReader(filePath)) {
                    FileModel model = gson.fromJson(reader, FileModel.class);
                    if (model == null || model.arenas == null) return;

                    for (ArenaDefinition candidate : model.arenas) {
                        if (candidate == null) continue;
                        String normalizedId = normalizeId(candidate.arenaId());
                        if (normalizedId == null) continue;
                        if (candidate.worldName() == null || candidate.worldName().isBlank()) continue;

                        ArenaDefinition normalized = new ArenaDefinition(
                                normalizedId,
                                candidate.worldName(),
                                candidate.pos1(),
                                candidate.pos2(),
                                candidate.enabled()
                        );
                        arenasById.put(normalizedId, normalized);
                    }
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[RR-NPC] Failed to load npc arenas.");
            }
        }
    }

    private void saveLocked() {
        if (filePath == null) return;
        try {
            Files.createDirectories(filePath.getParent());

            FileModel out = new FileModel();
            out.arenas = new ArrayList<>(arenasById.values());

            try (Writer writer = Files.newBufferedWriter(filePath)) {
                gson.toJson(out, writer);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-NPC] Failed to save npc arenas.");
        }
    }
}
