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
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Consumer;

public class Realm_Ruler extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Stand IDs
    private static final String STAND_EMPTY  = "Flag_Stand";
    private static final String STAND_RED    = "Flag_Stand_Red";
    private static final String STAND_BLUE   = "Flag_Stand_Blue";
    private static final String STAND_WHITE  = "Flag_Stand_White";
    private static final String STAND_YELLOW = "Flag_Stand_Yellow";

    // Weapon flag IDs (your duplicated swords-as-flags)
    private static final String FLAG_RED    = "Realm_Ruler_Flag_Red";
    private static final String FLAG_BLUE   = "Realm_Ruler_Flag_Blue";
    private static final String FLAG_WHITE  = "Realm_Ruler_Flag_White";
    private static final String FLAG_YELLOW = "Realm_Ruler_Flag_Yellow";

    private static final Message MSG_DEBUG_HIT =
            Message.raw("[RealmRuler] Flag stand interaction detected.");

    // Spam limits so logs don't turn into soup 🍲
    private static int USEBLOCK_DEBUG_LIMIT = 30;
    private static int ICCE_DEBUG_LIMIT = 200;

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

        // We keep this, but we won't rely on it (since your interaction is F-open UI)
        this.getEventRegistry().register(UseBlockEvent.Pre.class, this::onUseBlock);
        LOGGER.atInfo().log("Registered UseBlockEvent.Pre listener.");

        // This is the important part for the chest-style stand:
        registerItemContainerChangeEvent();
    }

    /**
     * Your current "F opens UI" behavior likely won't fire UseBlockEvent.Pre.
     * But keeping this listener helps us confirm what DOES fire, if anything.
     */
    private void onUseBlock(UseBlockEvent.Pre event) {
        InteractionType type = event.getInteractionType();

        // Keep broad for now
        if (type != InteractionType.Primary && type != InteractionType.Secondary && type != InteractionType.Use) {
            return;
        }

        BlockType blockType = event.getBlockType();
        String clickedId = safeBlockTypeId(blockType);

        InteractionContext ctx = event.getContext();
        ItemStack held = ctx.getHeldItem();
        String heldId = (held == null) ? "<empty>" : safeItemId(held);

        if (USEBLOCK_DEBUG_LIMIT-- > 0) {
            LOGGER.atInfo().log("[RR-USEBLOCK] type=%s clickedId=%s heldId=%s", type, clickedId, heldId);
            sendPlayerMessage(ctx, Message.raw("[RR-USEBLOCK] clickedId=" + clickedId + " heldId=" + heldId));
        }

        // If this ever fires on a stand, you'll see it.
        boolean isStand =
                STAND_EMPTY.equals(clickedId) ||
                        STAND_RED.equals(clickedId) ||
                        STAND_BLUE.equals(clickedId) ||
                        STAND_WHITE.equals(clickedId) ||
                        STAND_YELLOW.equals(clickedId);

        if (isStand) {
            sendPlayerMessage(ctx, MSG_DEBUG_HIT);
        }
    }

    /**
     * Registers ItemContainer$ItemContainerChangeEvent via reflection so you avoid
     * "cannot resolve symbol" issues if your SDK jar differs or IDE indexing is weird.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerItemContainerChangeEvent() {
        try {
            Class<?> evtClass = Class.forName(
                    "com.hypixel.hytale.server.core.inventory.container.ItemContainer$ItemContainerChangeEvent"
            );

            java.util.function.Consumer handler =
                    (java.util.function.Consumer) (Object e) -> onItemContainerChangeEvent(e);

            this.getEventRegistry().register((Class) evtClass, handler);

            LOGGER.atInfo().log("[RR-ICCE] Registered ItemContainerChangeEvent listener: %s", evtClass.getName());
        } catch (ClassNotFoundException cnf) {
            LOGGER.atWarning().withCause(cnf).log(
                    "[RR-ICCE] Could not find ItemContainer$ItemContainerChangeEvent at runtime. Class name/path may differ."
            );
        } catch (Throwable t) {
            LOGGER.atSevere().withCause(t).log(
                    "[RR-ICCE] Failed to register ItemContainerChangeEvent."
            );
        }

    }


    /**
     * First goal: PROVE this fires when you move items in the stand UI.
     * Second goal: learn what getters exist so we can locate the stand block position.
     */
    private void onItemContainerChangeEvent(Object event) {
        try {
            if (ICCE_DEBUG_LIMIT-- <= 0) return;

            Class<?> c = event.getClass();
            LOGGER.atInfo().log("[RR-ICCE] Fired: %s", c.getName());

            // NEW: try very hard to extract slot / changed slot info first
            logSlotSummary(event);

            // Keep your existing method-dump too (it helps discover getters)
            dumpInterestingNoArgMethods(event);

        } catch (Throwable t) {
            LOGGER.atSevere().withCause(t).log("[RR-ICCE] Handler crashed while processing event.");
        }
    }

    private void logSlotSummary(Object event) {
        // Common-ish getter names we want to probe first
        String[] candidates = new String[] {
                "getSlot", "getSlotIndex", "getChangedSlot", "getChangedSlotIndex",
                "getSlots", "getChangedSlots", "getModifiedSlots", "getDirtySlots",
                "slot", "slotIndex", "changedSlots"
        };

        for (String name : candidates) {
            try {
                Method m = event.getClass().getMethod(name);
                if (m.getParameterCount() != 0) continue;

                Object val = m.invoke(event);
                String rendered = renderValue(val);

                LOGGER.atInfo().log("[RR-ICCE]  SLOT-CANDIDATE %s() -> %s", name, rendered);
            } catch (Throwable ignored) {
                // ignore missing methods
            }
        }
    }

    private String renderValue(Object val) {
        if (val == null) return "<null>";

        // Arrays
        Class<?> cls = val.getClass();
        if (cls.isArray()) {
            int len = java.lang.reflect.Array.getLength(val);
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(val, i);
                if (i > 0) sb.append(", ");
                sb.append(String.valueOf(item));
            }
            sb.append("]");
            return sb.toString();
        }

        // Iterables (List/Set/etc)
        if (val instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object item : it) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(String.valueOf(item));
            }
            sb.append("]");
            return sb.toString();
        }

        return String.valueOf(val);
    }



    private void dumpInterestingNoArgMethods(Object obj) {
        Method[] methods = obj.getClass().getMethods();

        for (Method m : methods) {
            try {
                if (m.getParameterCount() != 0) continue;

                String name = m.getName();
                String low = name.toLowerCase(Locale.ROOT);

                // Filter to likely-useful info without dumping 200 irrelevant methods.
                boolean interesting =
                        low.contains("container") ||
                                low.contains("slot") ||
                                low.contains("item") ||
                                low.contains("stack") ||
                                low.contains("old") ||
                                low.contains("new") ||
                                low.contains("owner") ||
                                low.contains("entity") ||
                                low.contains("player") ||
                                low.contains("block") ||
                                low.contains("pos") ||
                                low.contains("world") ||
                                low.contains("location") ||
                                low.contains("source");

                if (!interesting) continue;

                Object val = m.invoke(obj);

                // Try to extract itemId if the return is an ItemStack-like object
                String rendered = String.valueOf(val);
                if (val != null) {
                    // If it looks like an ItemStack, try getItemId()
                    try {
                        Method getItemId = val.getClass().getMethod("getItemId");
                        Object itemId = getItemId.invoke(val);
                        rendered = rendered + " (itemId=" + String.valueOf(itemId) + ")";
                    } catch (Throwable ignored) {}
                }

                LOGGER.atInfo().log("[RR-ICCE]  %s() -> %s", name, rendered);

            } catch (Throwable ignored) {
                // never let debug dumping crash your plugin
            }
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

    private String safeItemId(ItemStack stack) {
        try {
            return String.valueOf(stack.getItemId());
        } catch (Throwable t) {
            return stack.toString();
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
