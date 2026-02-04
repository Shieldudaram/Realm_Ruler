package com.Chris__.realm_ruler.ui;

import com.Chris__.realm_ruler.core.LobbyHudState;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LobbyHudService {

    private record HudKey(String teamName, int waitingCount) {
    }

    private final Map<String, LobbyHud> hudByUuid = new ConcurrentHashMap<>();
    private final Map<String, EmptyHud> emptyHudByUuid = new ConcurrentHashMap<>();
    private final Map<String, HudKey> lastShownByUuid = new ConcurrentHashMap<>();

    public void renderForPlayer(String uuid, Player player, PlayerRef playerRef, LobbyHudState state) {
        if (uuid == null || uuid.isEmpty() || player == null || playerRef == null) return;

        LobbyHud hud = hudByUuid.computeIfAbsent(uuid, k -> new LobbyHud(playerRef));
        EmptyHud emptyHud = emptyHudByUuid.computeIfAbsent(uuid, k -> new EmptyHud(playerRef));
        CustomUIHud current = player.getHudManager().getCustomHud();

        boolean visible = state != null && state.visible();
        if (!visible) {
            if (current == hud) {
                hud.hide();
                player.getHudManager().setCustomHud(playerRef, emptyHud);
            }
            lastShownByUuid.remove(uuid);
            return;
        }

        String teamName = (state.teamName() == null) ? "" : state.teamName();
        int waitingCount = Math.max(0, state.waitingCount());

        HudKey desired = new HudKey(teamName, waitingCount);
        HudKey last = lastShownByUuid.get(uuid);
        if (!desired.equals(last) || current != hud) {
            hud.show(teamName, waitingCount);

            if (current != hud) {
                player.getHudManager().setCustomHud(playerRef, hud); // initial set (calls hud.show())
            } else {
                hud.show(); // force refresh (clear+rebuild)
            }

            lastShownByUuid.put(uuid, desired);
        }
    }
}
