package com.Chris__.realm_ruler.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * A "do nothing" HUD used to clear custom HUD state without sending a null command array.
 *
 * HudManager#setCustomHud(playerRef, null) currently sends a CustomHud packet with a null commands array,
 * which can crash the client (NullReferenceException) when it tries to apply the HUD commands.
 *
 * By switching to an EmptyHud, the client receives clear=true with an empty commands array instead.
 */
public final class EmptyHud extends CustomUIHud {

    public EmptyHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        // Intentionally empty: show() will still send clear=true with an empty commands array.
    }
}

