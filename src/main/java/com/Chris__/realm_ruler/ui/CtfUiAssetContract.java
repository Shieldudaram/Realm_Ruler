package com.Chris__.realm_ruler.ui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CtfUiAssetContract {
    public static final String HUD_LOBBY = "Hud/Timer/Lobby.ui";
    public static final String HUD_TIMER = "Hud/Timer/Timer.ui";
    public static final String HUD_FLAGS = "Hud/CTF/Flags.ui";
    public static final String PAGE_CTF_SHOP = "Pages/RealmRuler/CtfShop.ui";
    public static final String PAGE_CTF_MAP = "Pages/RealmRuler/CtfMap.ui";

    private static final String UI_ROOT = "Common/UI/Custom/";
    private static final String MANIFEST_RESOURCE = "manifest.json";
    private static final Pattern INCLUDES_ASSET_PACK_PATTERN =
            Pattern.compile("\"IncludesAssetPack\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private static final List<String> REQUIRED_UI_DOCUMENTS = List.of(
            HUD_LOBBY,
            HUD_TIMER,
            HUD_FLAGS,
            PAGE_CTF_SHOP,
            PAGE_CTF_MAP
    );

    private CtfUiAssetContract() {
    }

    public record ValidationResult(boolean manifestIncludesAssetPack, List<String> missingUiDocuments) {
        public boolean ready() {
            return manifestIncludesAssetPack && missingUiDocuments != null && missingUiDocuments.isEmpty();
        }
    }

    public static List<String> requiredUiDocuments() {
        return REQUIRED_UI_DOCUMENTS;
    }

    public static String toClasspathResourcePath(String uiDocumentPath) {
        if (uiDocumentPath == null) return null;
        return UI_ROOT + uiDocumentPath;
    }

    public static ValidationResult validate(ClassLoader classLoader) {
        boolean includesAssetPack = readIncludesAssetPack(classLoader);
        List<String> missingDocs = new ArrayList<>();
        for (String uiDoc : REQUIRED_UI_DOCUMENTS) {
            if (!hasUiDocument(classLoader, uiDoc)) {
                missingDocs.add(uiDoc);
            }
        }
        return new ValidationResult(includesAssetPack, List.copyOf(missingDocs));
    }

    public static boolean hasUiDocument(ClassLoader classLoader, String uiDocumentPath) {
        if (classLoader == null || uiDocumentPath == null || uiDocumentPath.isBlank()) return false;
        String classpathPath = toClasspathResourcePath(uiDocumentPath);
        return classpathPath != null && classLoader.getResource(classpathPath) != null;
    }

    public static boolean readIncludesAssetPack(ClassLoader classLoader) {
        if (classLoader == null) return false;
        try (InputStream in = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) return false;
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = INCLUDES_ASSET_PACK_PATTERN.matcher(json);
            if (!matcher.find()) return false;
            return Boolean.parseBoolean(matcher.group(1).toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
