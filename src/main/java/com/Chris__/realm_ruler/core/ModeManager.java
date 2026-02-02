package com.Chris__.Realm_Ruler.core;

import java.util.HashMap;
import java.util.Map;

/**
 * ModeManager is responsible for:
 *  1) Registering all available RealmMode implementations (ctf, lobby, etc.)
 *  2) Tracking which mode is currently active
 *  3) Forwarding ("dispatching") actions/events to the active mode
 *
 * Think of it like a small "mode router":
 * - Realm_Ruler (plugin) listens to server events (UseBlockEvent, etc.)
 * - Realm_Ruler forwards those events/actions to ModeManager
 * - ModeManager forwards them to the currently active mode
 *
 * This class is intentionally small:
 * - It does NOT interpret actions
 * - It does NOT know gameplay rules
 * - It only switches modes + forwards actions
 */
public class ModeManager {

    /**
     * Registry of all known modes, keyed by a stable string ID.
     *
     * Example IDs:
     *  - "ctf"
     *  - "lobby"
     *  - "debug"
     *
     * Notes:
     * - If you register two modes with the same ID, the later one will overwrite the earlier one.
     * - We use a HashMap because lookups are fast and mode count is small.
     */
    private final Map<String, RealmMode> modes = new HashMap<>();

    /**
     * The currently active mode.
     *
     * If this is null:
     * - no mode is active
     * - dispatchPlayerAction(...) will effectively do nothing
     */
    private RealmMode active;

    /**
     * Register a mode so that it can be activated later via setActive(modeId).
     *
     * Typical usage:
     * - Called during plugin initialization (startup).
     * - Each mode implementation is registered once.
     *
     * IMPORTANT:
     * - mode.id() should be unique and stable (used as the lookup key).
     */
    public void register(RealmMode mode) {
        modes.put(mode.id(), mode);
    }

    /**
     * Switch the active mode.
     *
     * Lifecycle rules:
     * 1) If there is an existing active mode, call active.onDisable()
     * 2) Look up the new mode by ID
     * 3) If found, set it as active and call active.onEnable()
     *
     * If the provided modeId is not registered:
     * - active will become null
     * - no mode will receive actions until another valid setActive(...) call happens
     *
     * NOTE:
     * - This method intentionally does not log or throw if modeId is missing
     *   (keeps behavior minimal; caller can add logs if desired).
     */
    public void setActive(String modeId) {
        if (active != null) {
            active.onDisable();
        }

        active = modes.get(modeId);

        if (active != null) {
            active.onEnable();
        }
    }

    /**
     * Forward an action/event to the currently active mode.
     *
     * Current design:
     * - "action" is an Object for flexibility.
     * - In practice, this may be a server event type (ex: UseBlockEvent.Pre)
     *   or a custom action object you create later.
     *
     * Behavior:
     * - If there is no active mode (active == null), this method does nothing.
     * - Otherwise it passes the action through to active.onPlayerAction(action).
     *
     * IMPORTANT:
     * - This method does not filter actions or enforce types.
     *   Each mode is responsible for checking the action type it supports.
     */
    public void dispatchPlayerAction(Object action) {
        if (active == null) {
            return;
        }
        active.onPlayerAction(action);
    }
}
// Example:
// modeManager.register(new CtfMode(...));
// modeManager.setActive("ctf");
// modeManager.dispatchPlayerAction(event);

