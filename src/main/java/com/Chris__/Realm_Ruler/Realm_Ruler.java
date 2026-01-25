package com.Chris__.Realm_Ruler;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

public class Realm_Ruler extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Stand IDs
    private static final String STAND_EMPTY  = "Flag_Stand";
    private static final String STAND_RED    = "Flag_Stand_Red";
    private static final String STAND_BLUE   = "Flag_Stand_Blue";
    private static final String STAND_WHITE  = "Flag_Stand_White";
    private static final String STAND_YELLOW = "Flag_Stand_Yellow";

    // Weapon flag IDs
    private static final String FLAG_RED    = "Realm_Ruler_Flag_Red";
    private static final String FLAG_BLUE   = "Realm_Ruler_Flag_Blue";
    private static final String FLAG_WHITE  = "Realm_Ruler_Flag_White";
    private static final String FLAG_YELLOW = "Realm_Ruler_Flag_Yellow";

    private static final Message MSG_EMPTY_HAND_REQUIRED =
            Message.raw("You need an empty hand to take the flag out of the stand.");

    private static final Message MSG_DEBUG_HIT =
            Message.raw("[RealmRuler] Flag stand interaction detected.");

    private static final Message MSG_SWAP_NOT_IMPLEMENTED =
            Message.raw("[RealmRuler] Block swap not wired yet (need set-block API).");

    public Realm_Ruler(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from " + this.getName() + " version " + this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up Realm Ruler " + this.getName());

        // Keep your test command for debugging
        this.getCommandRegistry().registerCommand(
                new ExampleCommand(this.getName(), this.getManifest().getVersion().toString())
        );

        // Flag stand logic
        this.getEventRegistry().register(UseBlockEvent.Pre.class, this::onUseBlock);

        LOGGER.atInfo().log("Registered UseBlockEvent.Pre listener for flag stands.");
    }

    private void onUseBlock(UseBlockEvent.Pre event) {
        InteractionType type = event.getInteractionType();

        // Keep broad for now (Primary/Secondary/Use)
        if (type != InteractionType.Primary && type != InteractionType.Secondary && type != InteractionType.Use) {
            return;
        }

        BlockType blockType = event.getBlockType();
        String clickedId = safeBlockTypeId(blockType);

        boolean isEmptyStand = STAND_EMPTY.equals(clickedId);
        boolean isColoredStand =
                STAND_RED.equals(clickedId) ||
                        STAND_BLUE.equals(clickedId) ||
                        STAND_WHITE.equals(clickedId) ||
                        STAND_YELLOW.equals(clickedId);

        if (!isEmptyStand && !isColoredStand) return;

        InteractionContext ctx = event.getContext();

        // Prove the handler fires
        LOGGER.atInfo().log("Flag stand click: type=%s blockId=%s target=%s",
                type, clickedId, String.valueOf(ctx.getTargetBlock()));

        // Optional: spam message to player so you SEE it live
        sendPlayerMessage(ctx, MSG_DEBUG_HIT);

        // IMPORTANT: cancel default interaction so Open_Container stops.
        // If you still see the container open after this, the event isn't firing.
        event.setCancelled(true);

        ItemStack held = ctx.getHeldItem(); // null = empty hand
        boolean handEmpty = (held == null);

        // =========================
        // REMOVE (colored stand): requires empty hand, puts a fresh flag into hand
        // =========================
        if (isColoredStand) {
            if (!handEmpty) {
                sendPlayerMessage(ctx, MSG_EMPTY_HAND_REQUIRED);
                return; // already cancelled
            }

            String flagId =
                    STAND_RED.equals(clickedId) ? FLAG_RED :
                            STAND_BLUE.equals(clickedId) ? FLAG_BLUE :
                                    STAND_WHITE.equals(clickedId) ? FLAG_WHITE :
                                            FLAG_YELLOW;

            // Create item stack using your SDK's constructor
            ItemStack freshFlag = new ItemStack(flagId, 1);

            // Force it to be held
            ctx.setHeldItem(freshFlag);

            // TODO: swap block in world to STAND_EMPTY
            sendPlayerMessage(ctx, MSG_SWAP_NOT_IMPLEMENTED);
            return;
        }

        // =========================
        // INSERT (empty stand): must be holding a flag, consumes held item
        // =========================
        if (isEmptyStand) {
            if (handEmpty) return;

            String heldId = held.getItemId();

            String newStandId =
                    FLAG_RED.equals(heldId) ? STAND_RED :
                            FLAG_BLUE.equals(heldId) ? STAND_BLUE :
                                    FLAG_WHITE.equals(heldId) ? STAND_WHITE :
                                            FLAG_YELLOW.equals(heldId) ? STAND_YELLOW :
                                                    null;

            if (newStandId == null) return; // not one of our flags

            // Consume the flag (stack size 1 rule)
            ctx.setHeldItem(null);

            // TODO: swap block in world to newStandId
            sendPlayerMessage(ctx, MSG_SWAP_NOT_IMPLEMENTED);
        }
    }

    private String safeBlockTypeId(BlockType blockType) {
        try {
            Object v = blockType.getClass().getMethod("getId").invoke(blockType);
            return String.valueOf(v);
        } catch (Exception e) {
            return blockType.toString();
        }
    }

    private void sendPlayerMessage(InteractionContext ctx, Message msg) {
        try {
            if (ctx.getCommandBuffer() == null) return;
            if (ctx.getEntity() == null || !ctx.getEntity().isValid()) return;

            Player player = (Player) ctx.getCommandBuffer().getComponent(ctx.getEntity(), Player.getComponentType());
            if (player != null) player.sendMessage(msg);
        } catch (Exception ignored) {
            // don't crash plugin for chat
        }
    }
}
