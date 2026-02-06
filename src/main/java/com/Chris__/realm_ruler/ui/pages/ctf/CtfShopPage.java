package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.match.CtfPointsRepository;
import com.Chris__.realm_ruler.match.CtfShopConfig;
import com.Chris__.realm_ruler.match.CtfShopConfigRepository;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public final class CtfShopPage extends InteractiveCustomUIPage<CtfShopPage.CtfShopEventData> {

    public static final String UI_PATH = "Pages/RealmRuler/CtfShop.ui";

    private static final int MAX_ENTRIES = 8;

    public static final class CtfShopEventData {
        public static final BuilderCodec<CtfShopEventData> CODEC = BuilderCodec.builder(CtfShopEventData.class, CtfShopEventData::new)
                .append(new KeyedCodec<>("id", Codec.STRING), CtfShopEventData::setId, CtfShopEventData::getId)
                .add()
                .build();

        private String id = "";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = (id == null) ? "" : id;
        }
    }

    private final CtfPointsRepository pointsRepository;
    private final CtfShopConfigRepository shopConfigRepository;
    private final BiFunction<String, Integer, ItemStack> itemStackFactory;

    public CtfShopPage(@Nonnull PlayerRef playerRef,
                       CtfPointsRepository pointsRepository,
                       CtfShopConfigRepository shopConfigRepository,
                       BiFunction<String, Integer, ItemStack> itemStackFactory) {
        super(playerRef, CustomPageLifetime.CanDismiss, CtfShopEventData.CODEC);
        this.pointsRepository = pointsRepository;
        this.shopConfigRepository = shopConfigRepository;
        this.itemStackFactory = itemStackFactory;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(UI_PATH);

        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String uuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();
        int points = (pointsRepository == null || uuid == null) ? 0 : pointsRepository.getPoints(uuid);
        ui.set("#PointsLabel.TextSpans", Message.raw("CTF Points: " + points));

        CtfShopConfig cfg = (shopConfigRepository == null) ? null : shopConfigRepository.getConfig();
        List<CtfShopConfig.ShopItem> enabled = new ArrayList<>();
        if (cfg != null && cfg.items != null) {
            for (CtfShopConfig.ShopItem it : cfg.items) {
                if (it == null) continue;
                if (!it.enabled) continue;
                if (it.id == null || it.id.isBlank()) continue;
                enabled.add(it);
                if (enabled.size() >= MAX_ENTRIES) break;
            }
        }

        for (int i = 0; i < MAX_ENTRIES; i++) {
            String entryId = "#Entry" + i;
            String nameId = "#Entry" + i + "Name.TextSpans";
            String costId = "#Entry" + i + "Cost.TextSpans";
            String buyButton = "#Entry" + i + "BuyButton";

            if (i >= enabled.size()) {
                ui.set(entryId + ".Visible", false);
                continue;
            }

            CtfShopConfig.ShopItem it = enabled.get(i);
            ui.set(entryId + ".Visible", true);
            ui.set(nameId, Message.raw(safe(it.name, it.id)));
            ui.set(costId, Message.raw(String.valueOf(Math.max(0, it.cost))));
            events.addEventBinding(CustomUIEventBindingType.Activating, buyButton, EventData.of("id", it.id));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, CtfShopEventData data) {
        if (ref == null || store == null || data == null) return;
        if (pointsRepository == null || shopConfigRepository == null || itemStackFactory == null) return;

        String shopId = (data.getId() == null) ? "" : data.getId().trim();
        if (shopId.isEmpty()) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || pr == null || pr.getUuid() == null) return;
        String uuid = pr.getUuid().toString();

        CtfShopConfig.ShopItem item = findItemById(shopId);
        if (item == null || !item.enabled) {
            player.sendMessage(Message.raw("[RealmRuler] That shop item is not available."));
            return;
        }

        int cost = Math.max(0, item.cost);
        int points = pointsRepository.getPoints(uuid);
        if (points < cost) {
            player.sendMessage(Message.raw("[RealmRuler] Not enough CTF points. (" + points + "/" + cost + ")"));
            return;
        }

        if (item.type == null || !item.type.equalsIgnoreCase("item")) {
            player.sendMessage(Message.raw("[RealmRuler] Unsupported reward type: " + String.valueOf(item.type)));
            return;
        }

        String itemId = (item.itemId == null) ? "" : item.itemId.trim();
        int amount = Math.max(1, item.amount);
        if (itemId.isEmpty()) {
            player.sendMessage(Message.raw("[RealmRuler] Shop item is misconfigured (missing itemId)."));
            return;
        }

        ItemStack stack = itemStackFactory.apply(itemId, amount);
        if (stack == null) {
            player.sendMessage(Message.raw("[RealmRuler] Failed to create item: " + itemId));
            return;
        }

        Inventory inv = player.getInventory();
        if (inv == null) return;

        SlotTarget target = findEmptySlot(inv);
        if (target == null) {
            player.sendMessage(Message.raw("[RealmRuler] Inventory full."));
            return;
        }

        if (!pointsRepository.spendPoints(uuid, cost)) {
            player.sendMessage(Message.raw("[RealmRuler] Not enough CTF points."));
            return;
        }

        target.container.setItemStackForSlot(target.slot, stack);
        player.sendInventory();
        player.sendMessage(Message.raw("[RealmRuler] Purchased: " + safe(item.name, item.id) + " (" + cost + " pts)"));

        UICommandBuilder update = new UICommandBuilder();
        update.set("#PointsLabel.TextSpans", Message.raw("CTF Points: " + pointsRepository.getPoints(uuid)));
        sendUpdate(update, false);
    }

    private static String safe(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return (fallback == null) ? "" : fallback;
    }

    private CtfShopConfig.ShopItem findItemById(String id) {
        CtfShopConfig cfg = shopConfigRepository.getConfig();
        if (cfg == null || cfg.items == null) return null;
        for (CtfShopConfig.ShopItem it : cfg.items) {
            if (it == null) continue;
            if (it.id == null) continue;
            if (it.id.equalsIgnoreCase(id)) return it;
        }
        return null;
    }

    private record SlotTarget(ItemContainer container, short slot) {
    }

    private static SlotTarget findEmptySlot(Inventory inv) {
        ItemContainer[] preferred = new ItemContainer[]{inv.getStorage(), inv.getBackpack(), inv.getHotbar()};
        for (ItemContainer c : preferred) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                ItemStack cur = c.getItemStack(s);
                if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) {
                    return new SlotTarget(c, s);
                }
            }
        }
        return null;
    }
}

