package com.Chris__.realm_ruler.ui;

import com.Chris__.realm_ruler.core.LobbyHudState;
import com.Chris__.realm_ruler.integration.MultipleHudBridge;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LobbyHudService {
    private static final String HUD_SLOT_ID = "RealmRuler_LobbyHud";

    private record HudKey(String teamName, int waitingCount, String waitingTeamsLine) {
    }

    private final MultipleHudBridge multipleHudBridge;
    private final Map<String, LobbyHud> hudByUuid = new ConcurrentHashMap<>();
    private final Set<String> shownHudByUuid = ConcurrentHashMap.newKeySet();
    private final Map<String, HudKey> lastShownByUuid = new ConcurrentHashMap<>();

    public LobbyHudService(MultipleHudBridge multipleHudBridge) {
        this.multipleHudBridge = multipleHudBridge;
    }

    public void renderForPlayer(String uuid, Player player, PlayerRef playerRef, LobbyHudState state) {
        if (uuid == null || uuid.isEmpty() || player == null || playerRef == null) return;

        LobbyHud hud = hudByUuid.computeIfAbsent(uuid, k -> new LobbyHud(playerRef));

        boolean visible = state != null && state.visible();
        if (!visible) {
            if (shownHudByUuid.remove(uuid)) {
                hud.hide();
                multipleHudBridge.hideCustomHud(player, playerRef, HUD_SLOT_ID);
            }
            lastShownByUuid.remove(uuid);
            return;
        }

        String teamName = (state.teamName() == null) ? "" : state.teamName();
        int waitingCount = Math.max(0, state.waitingCount());
        String waitingTeamsLine = (state.waitingTeamsLine() == null) ? "" : state.waitingTeamsLine();

        HudKey desired = new HudKey(teamName, waitingCount, waitingTeamsLine);
        HudKey last = lastShownByUuid.get(uuid);
        boolean isCurrentlyShown = shownHudByUuid.contains(uuid);
        if (!desired.equals(last) || !isCurrentlyShown) {
            hud.show(teamName, waitingCount, waitingTeamsLine);

            if (multipleHudBridge.setCustomHud(player, playerRef, HUD_SLOT_ID, hud)) {
                shownHudByUuid.add(uuid);
                lastShownByUuid.put(uuid, desired);
            }
        }
    }
}
