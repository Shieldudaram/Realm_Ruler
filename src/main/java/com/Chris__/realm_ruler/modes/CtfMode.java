package com.Chris__.realm_ruler.modes;

import com.Chris__.realm_ruler.Realm_Ruler;
import com.Chris__.realm_ruler.core.RealmMode;
import com.Chris__.realm_ruler.modes.ctf.CtfRules;
import com.Chris__.realm_ruler.modes.ctf.CtfState;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

import com.Chris__.realm_ruler.targeting.TargetingModels.BlockLocation;
import com.Chris__.realm_ruler.targeting.TargetingModels.LookTarget;
import com.Chris__.realm_ruler.targeting.TargetingModels.TargetingResult;

import java.lang.reflect.Method;

/**
 * CtfMode (Capture-the-Flag mode)  [MODE]
 *
 * This mode owns:
 * - CTF gameplay state (which flag is mounted on which stand)  -> CtfState (in-memory)
 * - CTF rules (IDs + policy)                                  -> CtfRules (pure)
 *
 * Realm_Ruler remains responsible for:
 * - Event wiring / dispatch
 * - TargetingService (WHERE did the interaction occur?)
 * - Tick-thread executor (runOnTick)
 * - World write seam (StandSwapService via rrSwapStandAt)
 */
public class CtfMode implements RealmMode {

    private static final Message MSG_NEED_EMPTY_HAND =
            Message.raw("Your hand must be empty to take the flag.");

    private final Realm_Ruler plugin;
    private final HytaleLogger logger;

    /**
     * Runtime state (not persisted across server/plugin restarts).
     * The world block variant is treated as a secondary source of truth so gameplay still works after reconnects.
     */
    private final CtfState state = new CtfState();

    public CtfMode(Realm_Ruler plugin) {
        this.plugin = plugin;
        this.logger = plugin.rrLogger();
    }

    @Override
    public String id() {
        return "ctf";
    }

    @Override
    public void onDisable() {
        // Clear stand state when the gamemode ends.
        state.clear();
    }

    @Override
    public void onPlayerAction(Object action) {
        if (!(action instanceof PlayerInteractionEvent event)) return;

        // Keep logging behavior identical by consuming the shared PI debug budget.
        boolean shouldLog = plugin.rrPi().consumeDebugBudget();

        InteractionType type = plugin.rrPi().safeInteractionType(event);
        String uuid = plugin.rrPi().safeUuid(event);
        String itemInHand = plugin.rrPi().safeItemInHandId(event);
        Object chain = plugin.rrPi().safeInteractionChain(event);

        // Only react to "F/use"
        if (type != InteractionType.Use) return;

        // Resolve (world,x,y,z) of interacted block
        TargetingResult tr = plugin.TargetingService().resolveTarget(uuid, event, chain);
        if (tr == null || tr.loc == null || tr.loc.world == null) return;

        BlockLocation loc = tr.loc;
        LookTarget look = tr.look;

        if (look != null && look.basePos != null && shouldLog) {
            long ageMs = (System.nanoTime() - look.nanoTime) / 1_000_000L;
            logger.atInfo().log("[RR-LOOK] uuid=%s lookBlock=%s pos=%d,%d,%d ageMs=%d",
                    uuid,
                    String.valueOf(look.blockId),
                    look.basePos.x, look.basePos.y, look.basePos.z,
                    ageMs);
        }

        // Confirm block at position
        String clickedId = plugin.rrTryGetBlockIdAt(loc.world, loc.x, loc.y, loc.z);
        if (clickedId == null) return;

        // Only continue if this is one of our stand variants
        if (!CtfRules.isStandId(clickedId)) return;

        final String clicked = clickedId;
        final String heldId = itemInHand;

        // IMPORTANT: Key format matches existing behavior for now.
        final String key = CtfState.standKey(plugin.rrWorldKey(loc.world), loc.x, loc.y, loc.z);

        // "Occupied" is derived from BOTH:
        // - runtime state (preferred when present)
        // - world block variant (survives reconnects/restarts)
        boolean stateSaysOccupied = state.hasFlag(key);
        boolean worldLooksOccupied = !CtfRules.STAND_EMPTY.equals(clicked);
        boolean occupied = stateSaysOccupied || worldLooksOccupied;

        boolean heldIsFlag = CtfRules.isCustomFlagId(heldId);
        boolean heldIsEmpty = CtfRules.isEmptyHandId(heldId);

        // DENY: occupied stand + non-empty hand (prevents all color-to-color swaps)
        if (occupied && !heldIsEmpty) {
            plugin.rrRunOnTick(() -> {
                Player p = plugin.rrResolvePlayer(uuid);
                if (p == null) return;

                p.sendMessage(MSG_NEED_EMPTY_HAND);
                tryPlayDenySound(p);
            });
            return;
        }

        // DEPOSIT: empty stand + holding custom flag
        if (!occupied && CtfRules.STAND_EMPTY.equals(clicked) && heldIsFlag) {
            plugin.rrRunOnTick(() -> {
                Player p = plugin.rrResolvePlayer(uuid);
                if (p == null) return;

                Inventory inv = p.getInventory();
                if (inv == null) return;

                ItemContainer hotbar = inv.getHotbar();
                if (hotbar == null) return;

                short slot = (short) (inv.getActiveHotbarSlot() & 0xFF);
                ItemStack inSlot = hotbar.getItemStack(slot);
                if (inSlot == null) return;

                // Ensure it's actually the flag we think it is
                if (!heldId.equals(inSlot.getItemId())) return;

                // Store exact ItemStack so we can give it back later (while server is running)
                state.putFlag(key, inSlot);

                // Remove from hand (flags don't stack, so remove 1)
                hotbar.removeItemStackFromSlot(slot, 1);

                // Visual swap -> colored
                String standVariant = CtfRules.selectDesiredStand(
                        CtfRules.STAND_EMPTY,
                        heldId,
                        plugin.rrPhase1ToggleBlueOnly()
                );
                plugin.rrSwapStandAt(loc, standVariant);

                // Sync
                p.sendInventory();
            });
            return;
        }

        // WITHDRAW: occupied stand + empty hand
        if (occupied && heldIsEmpty) {
            plugin.rrRunOnTick(() -> {
                Player p = plugin.rrResolvePlayer(uuid);
                if (p == null) return;

                Inventory inv = p.getInventory();
                if (inv == null) return;

                ItemContainer hotbar = inv.getHotbar();
                if (hotbar == null) return;

                short slot = (short) (inv.getActiveHotbarSlot() & 0xFF);

                // Disallow if their hand slot isn't empty
                ItemStack current = hotbar.getItemStack(slot);
                if (current != null && current.getQuantity() > 0) return;

                // Preferred path: return the exact stack we stored at deposit-time
                ItemStack stored = state.takeFlag(key);

                // Fallback path: state was lost (rejoin/restart/worldKey mismatch).
                // Reconstruct a 1x flag from the stand color, then empty the stand.
                if (stored == null) {
                    String fallbackFlagId = flagIdForStand(clicked);
                    if (fallbackFlagId != null) {
                        stored = plugin.rrCreateItemStackById(fallbackFlagId, 1);
                    }
                }

                if (stored == null) {
                    logger.atWarning().log("[RR-CTF] withdraw failed: occupied but no flag (key=%s clicked=%s)", key, clicked);
                    return;
                }

                // Put it into their hand slot
                hotbar.setItemStackForSlot(slot, stored);

                // Visual swap back to empty
                plugin.rrSwapStandAt(loc, CtfRules.STAND_EMPTY);

                p.sendInventory();
            });
            return;
        }

        // Otherwise: do nothing. (No auto-swaps and no UI logic here.)
    }

    private static String flagIdForStand(String standId) {
        if (CtfRules.STAND_RED.equals(standId)) return CtfRules.FLAG_RED;
        if (CtfRules.STAND_BLUE.equals(standId)) return CtfRules.FLAG_BLUE;
        if (CtfRules.STAND_WHITE.equals(standId)) return CtfRules.FLAG_WHITE;
        if (CtfRules.STAND_YELLOW.equals(standId)) return CtfRules.FLAG_YELLOW;
        return null;
    }

    /**
     * Best-effort deny sound (reflection-safe). If the API doesn't support it, this silently no-ops.
     */
    private static void tryPlayDenySound(Player player) {
        if (player == null) return;

        for (String mName : new String[]{"playSound", "playSoundEffect", "playUISound"}) {
            try {
                Method m = player.getClass().getMethod(mName, String.class);
                m.invoke(player, "ui.button.invalid");
                return;
            } catch (Throwable ignored) {}

            try {
                Method m = player.getClass().getMethod(mName, String.class, float.class, float.class);
                m.invoke(player, "ui.button.invalid", 1.0f, 1.0f);
                return;
            } catch (Throwable ignored) {}
        }
    }
}
