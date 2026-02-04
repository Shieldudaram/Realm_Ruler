package com.Chris__.realm_ruler.core;

/**
 * RealmMode represents a "game mode" (or ruleset) that Realm_Ruler can run.
 *
 * Examples of modes you might implement:
 * - Capture the Flag (CTF)
 * - Lobby / Free roam
 * - Debug / Test mode
 *
 * The plugin (Realm_Ruler) typically:
 *  1) Registers all modes in ModeManager during startup
 *  2) Chooses one active mode (ModeManager.setActive(...))
 *  3) Forwards relevant actions/events to the active mode via dispatchPlayerAction(...)
 *
 * Design goals:
 * - Keep mode logic isolated (so Realm_Ruler doesn't become a "god class")
 * - Make it easy to add new modes without rewriting event wiring
 * - Provide simple lifecycle hooks for setup/teardown
 *
 * Threading note (important for your project):
 * - If a mode mutates world state or player inventory, do that on the tick thread
 *   (or through your existing tick-queue mechanism).
 * - This interface does not enforce threading; it's a contract, not a scheduler.
 */
public interface RealmMode {

    /**
     * A stable identifier for this mode.
     *
     * Requirements:
     * - Must be stable across restarts (don't use random values).
     * - Should be unique among all registered modes.
     *
     * Examples: "ctf", "lobby", "debug"
     */
    String id();

    /**
     * Called when this mode becomes the active mode.
     *
     * Use this for:
     * - Initializing mode state
     * - Registering listeners specific to this mode (if you do that later)
     * - Sending a "mode enabled" message to players (optional)
     *
     * Default is no-op so simple modes don't have to implement it.
     */
    default void onEnable() {}

    /**
     * Called when this mode stops being the active mode.
     *
     * Use this for:
     * - Cleaning up mode state
     * - Removing mode-specific listeners (if you add those later)
     * - Resetting world artifacts that only exist while the mode is active (optional)
     *
     * Default is no-op so simple modes don't have to implement it.
     */
    default void onDisable() {}

    /**
     * Called whenever the plugin forwards an action/event to the active mode.
     *
     * Current design uses Object for maximum flexibility:
     * - You may pass raw server events (ex: UseBlockEvent.Pre) directly
     * - Or you may pass custom action objects (if you introduce them later)
     *
     * Mode implementations should:
     * - Check the action type (using instanceof)
     * - Ignore actions they don't support
     *
     * Example pattern:
     *   if (action instanceof UseBlockEvent.Pre e) { ... }
     */
    void onPlayerAction(Object action);
}
