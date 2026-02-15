package com.Chris__.realm_ruler.ui.pages.ctf;

import com.Chris__.realm_ruler.match.CtfShopService;
import com.Chris__.realm_ruler.ui.CtfUiAssetContract;
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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public final class CtfShopPage extends InteractiveCustomUIPage<CtfShopPage.CtfShopEventData> {

    public static final String UI_PATH = CtfUiAssetContract.PAGE_CTF_SHOP;
    private static final int MAX_ENTRIES = 16;

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

    private final CtfShopService shopService;

    public CtfShopPage(@Nonnull PlayerRef playerRef,
                       CtfShopService shopService) {
        super(playerRef, CustomPageLifetime.CanDismiss, CtfShopEventData.CODEC);
        this.shopService = shopService;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(UI_PATH);

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        String uuid = (playerRef == null || playerRef.getUuid() == null) ? null : playerRef.getUuid().toString();
        int points = (shopService == null || uuid == null) ? 0 : shopService.getPoints(uuid);
        ui.set("#PointsLabel.TextSpans", Message.raw("CTF Points: " + points));

        List<CtfShopService.ShopItemView> enabled = (shopService == null)
                ? List.of()
                : shopService.listEnabledItems();

        for (int index = 0; index < MAX_ENTRIES; index++) {
            String entryId = "#Entry" + index;
            String nameId = "#Entry" + index + "Name.TextSpans";
            String costId = "#Entry" + index + "Cost.TextSpans";
            String buyButton = "#Entry" + index + "BuyButton";

            if (index >= enabled.size()) {
                ui.set(entryId + ".Visible", false);
                continue;
            }

            CtfShopService.ShopItemView item = enabled.get(index);
            ui.set(entryId + ".Visible", true);
            ui.set(nameId, Message.raw(item.name()));
            ui.set(costId, Message.raw(String.valueOf(Math.max(0, item.cost()))));
            events.addEventBinding(CustomUIEventBindingType.Activating, buyButton, EventData.of("id", item.id()));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, CtfShopEventData data) {
        if (ref == null || store == null || data == null) return;
        if (shopService == null) return;

        String shopId = (data.getId() == null) ? "" : data.getId().trim();
        if (shopId.isEmpty()) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null || playerRef.getUuid() == null) return;

        String uuid = playerRef.getUuid().toString();
        CtfShopService.PurchaseResult result = shopService.purchase(player, uuid, shopId);
        player.sendMessage(Message.raw("[RealmRuler] " + result.message()));
        if (!result.success()) return;

        UICommandBuilder update = new UICommandBuilder();
        int remaining = (result.remainingPoints() >= 0) ? result.remainingPoints() : shopService.getPoints(uuid);
        update.set("#PointsLabel.TextSpans", Message.raw("CTF Points: " + remaining));
        sendUpdate(update, false);
    }
}
