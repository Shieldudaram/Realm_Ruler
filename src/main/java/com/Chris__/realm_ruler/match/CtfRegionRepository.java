package com.Chris__.realm_ruler.match;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

public final class CtfRegionRepository {

    private static final String FILE_NAME = "ctf_region.json";
    private static final int FILE_VERSION = 1;

    public record BlockPos(int x, int y, int z) {
    }

    public record RegionDefinition(String worldName, BlockPos pos1, BlockPos pos2, boolean enabled) {
        public boolean hasBounds() {
            return worldName != null && !worldName.isBlank() && pos1 != null && pos2 != null;
        }

        public boolean contains(String currentWorld, double x, double y, double z) {
            if (!enabled) return false;
            if (!hasBounds()) return false;
            if (currentWorld == null || !worldName.equals(currentWorld)) return false;

            int blockX = (int) Math.floor(x);
            int blockY = (int) Math.floor(y);
            int blockZ = (int) Math.floor(z);

            int minX = minX();
            int maxX = maxX();
            int minY = minY();
            int maxY = maxY();
            int minZ = minZ();
            int maxZ = maxZ();

            return blockX >= minX && blockX <= maxX
                    && blockY >= minY && blockY <= maxY
                    && blockZ >= minZ && blockZ <= maxZ;
        }

        public int minX() {
            return Math.min(pos1.x(), pos2.x());
        }

        public int maxX() {
            return Math.max(pos1.x(), pos2.x());
        }

        public int minY() {
            return Math.min(pos1.y(), pos2.y());
        }

        public int maxY() {
            return Math.max(pos1.y(), pos2.y());
        }

        public int minZ() {
            return Math.min(pos1.z(), pos2.z());
        }

        public int maxZ() {
            return Math.max(pos1.z(), pos2.z());
        }
    }

    public record RandomPoint(String worldName, int x, int y, int z) {
    }

    private static final class FileModel {
        int version = FILE_VERSION;
        String worldName;
        BlockPos pos1;
        BlockPos pos2;
        boolean enabled = true;
    }

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private RegionDefinition region;

    public CtfRegionRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        load();
    }

    public boolean create(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;

        synchronized (lock) {
            region = new RegionDefinition(worldName, null, null, true);
            saveLocked();
            return true;
        }
    }

    public boolean setPos1(BlockPos pos) {
        return updatePos(pos, true);
    }

    public boolean setPos2(BlockPos pos) {
        return updatePos(pos, false);
    }

    public boolean clear() {
        synchronized (lock) {
            if (region == null) return false;
            region = null;
            saveLocked();
            return true;
        }
    }

    public RegionDefinition get() {
        synchronized (lock) {
            return region;
        }
    }

    public boolean hasBounds() {
        RegionDefinition current = get();
        return current != null && current.enabled() && current.hasBounds();
    }

    public boolean contains(String worldName, double x, double y, double z) {
        RegionDefinition current = get();
        if (current == null) return false;
        return current.contains(worldName, x, y, z);
    }

    public RandomPoint randomPoint() {
        RegionDefinition current = get();
        if (current == null || !current.enabled() || !current.hasBounds()) return null;

        int x = ThreadLocalRandom.current().nextInt(current.minX(), current.maxX() + 1);
        int y = ThreadLocalRandom.current().nextInt(current.minY(), current.maxY() + 1);
        int z = ThreadLocalRandom.current().nextInt(current.minZ(), current.maxZ() + 1);
        return new RandomPoint(current.worldName(), x, y, z);
    }

    private boolean updatePos(BlockPos pos, boolean first) {
        if (pos == null) return false;
        synchronized (lock) {
            if (region == null) return false;
            region = new RegionDefinition(
                    region.worldName(),
                    first ? pos : region.pos1(),
                    first ? region.pos2() : pos,
                    region.enabled()
            );
            saveLocked();
            return true;
        }
    }

    private void load() {
        if (filePath == null) return;
        synchronized (lock) {
            region = null;
            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    saveLocked();
                    return;
                }

                try (Reader reader = Files.newBufferedReader(filePath)) {
                    FileModel model = gson.fromJson(reader, FileModel.class);
                    if (model == null) return;
                    if (model.worldName == null || model.worldName.isBlank()) return;
                    region = new RegionDefinition(
                            model.worldName,
                            model.pos1,
                            model.pos2,
                            model.enabled
                    );
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[RR-CTF] Failed to load CTF region.");
            }
        }
    }

    private void saveLocked() {
        if (filePath == null) return;
        try {
            Files.createDirectories(filePath.getParent());

            FileModel out = new FileModel();
            if (region != null) {
                out.worldName = region.worldName();
                out.pos1 = region.pos1();
                out.pos2 = region.pos2();
                out.enabled = region.enabled();
            }

            try (Writer writer = Files.newBufferedWriter(filePath)) {
                gson.toJson(out, writer);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to save CTF region.");
        }
    }
}
