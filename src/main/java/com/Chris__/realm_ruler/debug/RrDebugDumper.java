package com.Chris__.Realm_Ruler.debug;

import com.hypixel.hytale.logger.HytaleLogger;

import java.lang.reflect.Method;
import java.util.Locale;

public final class RrDebugDumper {

    private RrDebugDumper() {}

    /**
     * Reflection-based “surface dump” used during reverse engineering.
     *
     * What it does:
     * - Logs method names and field names that look relevant to positions and hits.
     *
     * If you want less log noise:
     * - gate the call site behind a flag.
     */
    public static void dumpInteractionSurface(HytaleLogger logger, Object obj) {
        if (obj == null) return;

        try {
            Class<?> cls = obj.getClass();
            logger.atInfo().log("[RR-PI] ---- Phase3 surface dump (%s) ----", cls.getName());

            int shown = 0;
            for (Method m : cls.getMethods()) {
                if (shown >= 40) break;
                if (m.getParameterCount() != 0) continue;

                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!containsAnyLower(n, new String[]{"hit","target","block","pos","position","world","coord","location"})) continue;

                logger.atInfo().log("[RR-PI]   m: %s -> %s", m.getName(), m.getReturnType().getTypeName());
                shown++;
            }

            shown = 0;
            for (var f : cls.getDeclaredFields()) {
                if (shown >= 40) break;

                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!containsAnyLower(n, new String[]{"hit","target","block","pos","position","world","coord","location"})) continue;

                logger.atInfo().log("[RR-PI]   f: %s : %s", f.getName(), f.getType().getTypeName());
                shown++;
            }
        } catch (Throwable ignored) {
            // Intentionally silent: this is debug-only tooling.
        }
    }

    // Keep semantics identical to Realm_Ruler.containsAny(): caller provides lowercased haystack + needles.
    private static boolean containsAnyLower(String haystackLower, String[] needlesLower) {
        for (String n : needlesLower) {
            if (haystackLower.contains(n)) return true;
        }
        return false;
    }
}
