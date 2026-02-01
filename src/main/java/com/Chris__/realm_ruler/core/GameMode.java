package com.Chris__.Realm_Ruler.core;

public interface GameMode {
    String id();
    default void onEnable() {}
    default void onDisable() {}
    void onPlayerAction(Object action);
}
