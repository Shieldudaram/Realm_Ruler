package com.Chris__.Realm_Ruler.modes.ctf;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CTF state container.
 * Owns "what flag is stored in which stand".
 *
 * NOTE: Key format currently matches existing behavior: "x|y|z".
 */
public final class CtfState {

    private final Map<String, ItemStack> flagByStandKey = new ConcurrentHashMap<>();

    public boolean hasFlag(String standKey) {
        return flagByStandKey.containsKey(standKey);
    }

    public ItemStack peekFlag(String standKey) {
        return flagByStandKey.get(standKey);
    }

    public void putFlag(String standKey, ItemStack flagStack) {
        flagByStandKey.put(standKey, flagStack);
    }

    public ItemStack takeFlag(String standKey) {
        return flagByStandKey.remove(standKey);
    }

    public void clear() {
        flagByStandKey.clear();
    }
}
