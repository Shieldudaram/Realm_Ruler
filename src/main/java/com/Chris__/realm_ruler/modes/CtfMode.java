package com.Chris__.Realm_Ruler.modes;

import com.Chris__.Realm_Ruler.Realm_Ruler;
import com.Chris__.Realm_Ruler.core.RealmMode;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import pl.grzegorz2047.hytale.lib.playerinteractlib.PlayerInteractionEvent;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CtfMode (Capture-the-Flag mode)  [MODE]
 *
 * This mode now owns:
 * - CTF gameplay state (which flag is mounted on which stand)
 * - CTF rules for deposit/withdraw + stand swaps (migrated from Realm_Ruler.handleCtfAction)
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
    private final Map<String, ItemStack> flagByStandKey = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Asset IDs (must match JSON IDs exactly)
    // -------------------------------------------------------------------------

    /** Stand block IDs (placed in the world). */
    private static final String STAND_EMPTY  = "Flag_Stand";
    private static final String STAND_RED    = "Flag_Stand_Red";
    private static final String STAND_BLUE   = "Flag_Stand_Blue";
    private static final String STAND_WHITE  = "Flag_Stand_White";
    private static final String STAND_YELLOW = "Flag_Stand_Yellow";

    /** Flag item IDs (items in player inventory / hand). */
    private static final String FLAG_RED    = "Realm_Ruler_Flag_Red";
    private static final String FLAG_BLUE   = "Realm_Ruler_Flag_Blue";
    private static final String FLAG_WHITE  = "Realm_Ruler_Flag_White";
    private static final String FLAG_YELLOW = "Realm_Ruler_Flag_Yellow";

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
        // Requirement from you: clear stand state when the gamemode ends.
        flagByStandKey.clear();
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
        boolean shouldLog = plugin.rrConsumePiDebugBudget();

        InteractionType type = plugin.rrSafeInteractionType(event);
        String uuid = plugin.rrSafeUuid(event);
        String itemInHand = plugin.rrSafeItemInHandId(event);
        Object chain = plugin.rrSafeInteractionChain(event);

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
        if (!isStandId(clickedId)) return;

        // Choose desired variant using the same rules as before
        String desiredStand = selectDesiredStand(clickedId, itemInHand);

        if (!plugin.rrStandSwapEnabled()) {
            logger.atInfo().log("[RR] (dry-run) would swap stand @ %d,%d,%d from %s -> %s",
                    loc.x, loc.y, loc.z, clickedId, desiredStand);
            return;
        }

        final String clicked = clickedId;
        final String heldId = itemInHand;

        // IMPORTANT: Use a stable key. (Coordinates only for now, matching current behavior.)
        final String key = loc.x + "|" + loc.y + "|" + loc.z;

        boolean standHasStoredFlag = flagByStandKey.containsKey(key);
        boolean heldIsFlag = isCustomFlagId(heldId);
        boolean heldIsEmpty = isEmptyHandId(heldId);

        // Deposit: empty stand + holding custom flag + no stored flag yet
        if (!standHasStoredFlag && STAND_EMPTY.equals(clicked) && heldIsFlag) {
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
                flagByStandKey.put(key, inSlot);

                // Remove from hand (flags don't stack, so remove 1)
                hotbar.removeItemStackFromSlot(slot, 1);

                // Visual swap -> colored
                String standVariant = selectDesiredStand(STAND_EMPTY, heldId);
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

                ItemStack stored = flagByStandKey.remove(key);
                if (stored == null) return;

                // Put it into their hand slot
                hotbar.setItemStackForSlot(slot, stored);

                // Visual swap back to empty
                plugin.rrSwapStandAt(loc, STAND_EMPTY);

                p.sendInventory();
            });
            return;
        }

        // Preserve current behavior: always swap to the computed desired variant
        plugin.rrSwapStandAt(loc, desiredStand);
    }

    // -------------------------------------------------------------------------
    // RULES / HELPERS (mode-owned)
    // -------------------------------------------------------------------------

    private boolean isStandId(String id) {
        return STAND_EMPTY.equals(id)
                || STAND_RED.equals(id)
                || STAND_BLUE.equals(id)
                || STAND_WHITE.equals(id)
                || STAND_YELLOW.equals(id);
    }

    private String selectDesiredStand(String clickedStandId, String itemInHandId) {
        if (plugin.rrPhase1ToggleBlueOnly()) {
            return STAND_BLUE.equals(clickedStandId) ? STAND_EMPTY : STAND_BLUE;
        }

        // Phase 2+ behavior: stand depends on held flag item.
        if (FLAG_RED.equals(itemInHandId)) return STAND_RED;
        if (FLAG_BLUE.equals(itemInHandId)) return STAND_BLUE;
        if (FLAG_WHITE.equals(itemInHandId)) return STAND_WHITE;
        if (FLAG_YELLOW.equals(itemInHandId)) return STAND_YELLOW;

        return STAND_EMPTY;
    }

    private static boolean isCustomFlagId(String itemId) {
        if (itemId == null) return false;
        return itemId.equals(FLAG_RED)
                || itemId.equals(FLAG_BLUE)
                || itemId.equals(FLAG_WHITE)
                || itemId.equals(FLAG_YELLOW);
    }

    private static boolean isEmptyHandId(String itemId) {
        if (itemId == null) return true;
        String s = itemId.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.equals("<empty>") || s.equals("air") || s.endsWith(":air");
    }
}
