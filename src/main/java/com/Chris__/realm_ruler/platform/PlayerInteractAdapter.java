package com.Chris__.Realm_Ruler.platform;

import com.hypixel.hytale.protocol.InteractionType;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

import java.lang.reflect.Method;

/**
 * PlayerInteractAdapter
 *
 * Centralizes safe extraction from PlayerInteractionEvent.
 * IMPORTANT: PlayerInteractLib often uses record-style accessors (uuid(), interactionType(), etc.)
 * rather than JavaBean getters (getUuid(), getInteractionType()).
 */
public final class PlayerInteractAdapter {

    private int debugRemaining = 400;

    /** Returns true if we should print a debug log (decrements the remaining budget). */
    public boolean consumeDebugBudget() {
        return (debugRemaining-- > 0);
    }

    public InteractionType safeInteractionType(PlayerInteractionEvent e) {
        // Primary: record accessor
        try { return e.interactionType(); } catch (Throwable ignored) {}

        // Fallback: reflection on common names (in case lib changes)
        Object v = safeCall(e, "interactionType", "getInteractionType");
        if (v instanceof InteractionType it) return it;

        if (v != null) {
            try { return InteractionType.valueOf(String.valueOf(v)); } catch (Throwable ignored) {}
        }
        return null;
    }

    public String safeUuid(PlayerInteractionEvent e) {
        // Primary: record accessor
        try { return e.uuid(); } catch (Throwable ignored) {}

        // Fallback
        Object v = safeCall(e, "uuid", "getUuid");
        return (v == null) ? "<null>" : String.valueOf(v);
    }

    public String safeItemInHandId(PlayerInteractionEvent e) {
        // Primary: record accessor
        try { return e.itemInHandId(); } catch (Throwable ignored) {}

        // Fallback
        Object v = safeCall(e, "itemInHandId", "getItemInHandId");
        return (v == null) ? "<empty>" : String.valueOf(v);
    }

    public Object safeInteractionChain(PlayerInteractionEvent e) {
        // Primary: record accessor
        try { return e.interaction(); } catch (Throwable ignored) {}

        // Fallback
        return safeCall(e, "interaction", "getInteraction", "interactionChain", "getInteractionChain");
    }

    private static Object safeCall(Object obj, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = obj.getClass().getMethod(name);
                if (m.getParameterCount() != 0) continue;
                return m.invoke(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
