package com.Chris__.realm_ruler.match;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CtfShopConfigRepository {

    private static final String FILE_NAME = "ctf_shop.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private volatile CtfShopConfig cached = null;

    public CtfShopConfigRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        reload();
    }

    public CtfShopConfig getConfig() {
        CtfShopConfig c = cached;
        if (c != null) return c;
        c = defaultConfig();
        cached = c;
        return c;
    }

    public void reload() {
        if (filePath == null) {
            cached = defaultConfig();
            return;
        }

        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                CtfShopConfig cfg = defaultConfig();
                cached = cfg;
                save(cfg);
                return;
            }

            try (Reader r = Files.newBufferedReader(filePath)) {
                CtfShopConfig loaded = gson.fromJson(r, CtfShopConfig.class);
                cached = (loaded == null) ? defaultConfig() : loaded;
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to load ctf shop config; using defaults.");
            cached = defaultConfig();
        }
    }

    private void save(CtfShopConfig cfg) {
        if (filePath == null || cfg == null) return;
        try (Writer w = Files.newBufferedWriter(filePath)) {
            gson.toJson(cfg, w);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to save default ctf shop config.");
        }
    }

    private static CtfShopConfig defaultConfig() {
        CtfShopConfig cfg = new CtfShopConfig();
        cfg.version = 1;

        CtfShopConfig.ShopItem example = new CtfShopConfig.ShopItem();
        example.id = "example";
        example.enabled = false;
        example.name = "Example Reward";
        example.cost = 50;
        example.type = "item";
        example.itemId = "REPLACE_ME";
        example.amount = 1;

        cfg.items.add(example);
        return cfg;
    }
}

