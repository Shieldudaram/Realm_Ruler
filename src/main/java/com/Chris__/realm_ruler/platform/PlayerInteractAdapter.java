package com.Chris__.realm_ruler.platform;

import com.hypixel.hytale.protocol.InteractionType;

import java.lang.reflect.Method;

/**
 * PlayerInteractAdapter
 *
 * Centralizes safe extraction from PlayerInteractLib's PlayerInteractionEvent (via reflection).
 * IMPORTANT: PlayerInteractLib often uses record-style accessors (uuid(), interactionType(), etc.)
 * rather than JavaBean getters (getUuid(), getInteractionType()).
 */
public final class PlayerInteractAdapter {

    private static final String PI_EVENT_CLASS =
            "pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent";

    private int debugRemaining = 400;

    /** Returns true if we should print a debug log (decrements the remaining budget). */
    public boolean consumeDebugBudget() {
        return (debugRemaining-- > 0);
    }

    public boolean isPlayerInteractionEvent(Object e) {
        return e != null && PI_EVENT_CLASS.equals(e.getClass().getName());
    }

    public InteractionType safeInteractionType(Object e) {
        if (e == null) return null;

        // Fallback: reflection on common names (in case lib changes)
        Object v = safeCall(e, "interactionType", "getInteractionType");
        if (v instanceof InteractionType it) return it;

        if (v != null) {
            try { return InteractionType.valueOf(String.valueOf(v)); } catch (Throwable ignored) {}
        }
        return null;
    }

    public String safeUuid(Object e) {
        if (e == null) return "<null>";

        // Fallback
        Object v = safeCall(e, "uuid", "getUuid", "playerUuid", "getPlayerUuid");
        return (v == null) ? "<null>" : String.valueOf(v);
    }

    public String safeItemInHandId(Object e) {
        if (e == null) return "<empty>";

        // Fallback
        Object v = safeCall(e, "itemInHandId", "getItemInHandId");
        return (v == null) ? "<empty>" : String.valueOf(v);
    }

    public Object safeInteractionChain(Object e) {
        if (e == null) return null;

        // Fallback
        return safeCall(e, "interaction", "getInteraction", "interactionChain", "getInteractionChain");
    }

    /**
     * Best-effort cancellation. PlayerInteractLib versions differ; some events are cancellable, some aren't.
     * Returns true if we successfully invoked a cancellation-style method.
     */
    public boolean tryCancel(Object e) {
        if (e == null) return false;

        // Common cancel patterns across event libs
        for (String mName : new String[]{"setCancelled", "setCanceled", "cancel", "setHandled"}) {
            // setX(boolean)
            try {
                Method m = e.getClass().getMethod(mName, boolean.class);
                m.invoke(e, true);
                return true;
            } catch (Throwable ignored) {}

            // cancel() / setHandled() with no args
            try {
                Method m = e.getClass().getMethod(mName);
                m.invoke(e);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
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
