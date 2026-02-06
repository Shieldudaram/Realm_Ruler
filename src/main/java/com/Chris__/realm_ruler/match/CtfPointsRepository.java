package com.Chris__.realm_ruler.match;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class CtfPointsRepository {

    private static final String FILE_NAME = "ctf_points.json";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Integer>>() {
    }.getType();

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private final Map<String, Integer> pointsByUuid = new HashMap<>();

    public CtfPointsRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        load();
    }

    public int getPoints(String uuid) {
        if (uuid == null || uuid.isBlank()) return 0;
        synchronized (lock) {
            return Math.max(0, pointsByUuid.getOrDefault(uuid, 0));
        }
    }

    public void addPoints(String uuid, int delta) {
        if (uuid == null || uuid.isBlank()) return;
        if (delta == 0) return;

        synchronized (lock) {
            int cur = Math.max(0, pointsByUuid.getOrDefault(uuid, 0));
            int next = cur + delta;
            if (next < 0) next = 0;
            pointsByUuid.put(uuid, next);
            save();
        }
    }

    public boolean spendPoints(String uuid, int cost) {
        if (uuid == null || uuid.isBlank()) return false;
        if (cost <= 0) return true;

        synchronized (lock) {
            int cur = Math.max(0, pointsByUuid.getOrDefault(uuid, 0));
            if (cur < cost) return false;
            pointsByUuid.put(uuid, cur - cost);
            save();
            return true;
        }
    }

    private void load() {
        if (filePath == null) return;
        synchronized (lock) {
            pointsByUuid.clear();
            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    save();
                    return;
                }

                try (Reader r = Files.newBufferedReader(filePath)) {
                    Map<String, Integer> loaded = gson.fromJson(r, MAP_TYPE);
                    if (loaded != null) {
                        for (Map.Entry<String, Integer> e : loaded.entrySet()) {
                            if (e.getKey() == null || e.getKey().isBlank()) continue;
                            int v = (e.getValue() == null) ? 0 : Math.max(0, e.getValue());
                            pointsByUuid.put(e.getKey(), v);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[RR-CTF] Failed to load ctf points.");
            }
        }
    }

    private void save() {
        if (filePath == null) return;
        try {
            Files.createDirectories(filePath.getParent());
            try (Writer w = Files.newBufferedWriter(filePath)) {
                gson.toJson(pointsByUuid, MAP_TYPE, w);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to save ctf points.");
        }
    }
}

