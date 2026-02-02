package com.Chris__.Realm_Ruler.modes.ctf;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CTF state container.
 * Owns "what flag is stored in which stand".
 *
 * Stand key format (multiworld-safe):
 *   "worldKey|x|y|z"
 *
 * Why:
 * - Prevents collisions when multiple worlds/instances exist with the same coordinates.
 * - Keeps key construction in one place so future changes are localized.
 */
public final class CtfState {

    private final Map<String, ItemStack> flagByStandKey = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Key helpers
    // -------------------------------------------------------------------------

    public static String standKey(String worldKey, int x, int y, int z) {
        // worldKey should be stable per world/instance; empty keys still work but are discouraged.
        String wk = (worldKey == null || worldKey.isBlank()) ? "<world?>" : worldKey;
        return wk + "|" + x + "|" + y + "|" + z;
    }

    // -------------------------------------------------------------------------
    // State accessors
    // -------------------------------------------------------------------------

    public boolean hasFlag(String standKey) {
        return flagByStandKey.containsKey(standKey);
    }

    public ItemStack peekFlag(String standKey) {
        return flagByStandKey.get(standKey);
    }

    public void putFlag(String standKey, ItemStack flagStack) {
        if (standKey == null || flagStack == null) return;
        flagByStandKey.put(standKey, flagStack);
    }

    public ItemStack takeFlag(String standKey) {
        if (standKey == null) return null;
        return flagByStandKey.remove(standKey);
    }

    public void clear() {
        flagByStandKey.clear();
    }
}
