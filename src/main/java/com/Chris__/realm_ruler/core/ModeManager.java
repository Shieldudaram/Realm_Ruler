package com.Chris__.Realm_Ruler.core;

import java.util.HashMap;
import java.util.Map;

public class ModeManager {
    private final Map<String, GameMode> modes = new HashMap<>();
    private GameMode active;

    public void register(GameMode mode) { modes.put(mode.id(), mode); }

    public void setActive(String modeId) {
        if (active != null) active.onDisable();
        active = modes.get(modeId);
        if (active != null) active.onEnable();
    }

    public void dispatchPlayerAction(Object action) {
        if (active == null) return;
        active.onPlayerAction(action);
    }
}
