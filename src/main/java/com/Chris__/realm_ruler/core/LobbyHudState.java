package com.Chris__.realm_ruler.core;

/**
 * Minimal lobby HUD snapshot for a specific player.
 * Produced by game/match code, consumed by the HUD renderer each tick.
 */
public record LobbyHudState(boolean visible, String teamName, int waitingCount) {
}

