package com.Chris__.Realm_Ruler.modes;

import com.Chris__.Realm_Ruler.Realm_Ruler;
import com.Chris__.Realm_Ruler.core.RealmMode;
import com.Chris__.Realm_Ruler.modes.ctf.CtfRules;
import com.Chris__.Realm_Ruler.modes.ctf.CtfState;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

/**
 * CtfMode (Capture-the-Flag mode)  [MODE]
 *
 * This mode owns:
 * - CTF gameplay state (which flag is mounted on which stand)
 * - CTF rules (via CtfRules)
 *
 * Realm_Ruler remains responsible for:
 * - Event wiring / dispatch
 * - TargetingService (WHERE did the interaction occur?)
 * - Tick-thread executor (runOnTick)
 * - World write seam (StandSwapService via rrSwapStandAt)
 */
public class CtfMode implements RealmMode {

    private final Realm_Ruler plugin;
    private final HytaleLogger logger;

    // CTF STATE: which flag item is currently "mounted" on each stand
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
        /*
         * The plugin forwards actions/events to the active mode as plain Object.
         * This mode should:
         *  - Handle only the action types it understands
         *  - Ignore everything else (do NOT throw or assume the type)
         */
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
        Realm_Ruler.TargetingResult tr = plugin.rrTargetingService().resolveTarget(uuid, event, chain);
        if (tr == null || tr.loc == null || tr.loc.world == null) return;

        Realm_Ruler.BlockLocation loc = tr.loc;
        Realm_Ruler.LookTarget look = tr.look;

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

        // Choose desired variant using the same rules as before
        String desiredStand = CtfRules.selectDesiredStand(
                clickedId,
                itemInHand,
                plugin.rrPhase1ToggleBlueOnly()
        );

        if (!plugin.rrStandSwapEnabled()) {
            logger.atInfo().log("[RR] (dry-run) would swap stand @ %d,%d,%d from %s -> %s",
                    loc.x, loc.y, loc.z, clickedId, desiredStand);
            return;
        }

        final String clicked = clickedId;
        final String heldId = itemInHand;

        // IMPORTANT: Key format matches existing behavior for now.
        final String key = loc.x + "|" + loc.y + "|" + loc.z;

        boolean standHasStoredFlag = state.hasFlag(key);
        boolean heldIsFlag = CtfRules.isCustomFlagId(heldId);
        boolean heldIsEmpty = CtfRules.isEmptyHandId(heldId);

        // Deposit: empty stand + holding custom flag + no stored flag yet
        if (!standHasStoredFlag && CtfRules.STAND_EMPTY.equals(clicked) && heldIsFlag) {
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

                // Store exact ItemStack so we can give it back later
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

        // Withdraw: stand has stored flag + empty hand
        if (standHasStoredFlag && heldIsEmpty) {
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

                ItemStack stored = state.takeFlag(key);
                if (stored == null) return;

                // Put it into their hand slot
                hotbar.setItemStackForSlot(slot, stored);

                // Visual swap back to empty
                plugin.rrSwapStandAt(loc, CtfRules.STAND_EMPTY);

                p.sendInventory();
            });
            return;
        }

        // Preserve current behavior: always swap to the computed desired variant
        plugin.rrSwapStandAt(loc, desiredStand);
    }
}
