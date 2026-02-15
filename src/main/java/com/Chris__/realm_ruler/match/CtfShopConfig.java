package com.Chris__.realm_ruler.match;

import java.util.ArrayList;
import java.util.List;

public final class CtfShopConfig {
    public int version = 1;
    public List<ShopItem> items = new ArrayList<>();

    public static final class BundleItem {
        public String itemId;
        public int amount = 1;
    }

    public static final class ShopItem {
        public String id;
        public boolean enabled;
        public String name;
        public int cost;
        public String type;
        public String itemId;
        public int amount;
        public String availability = "any";
        public String teamRule = "any";
        public String team;
        public List<BundleItem> bundleItems = new ArrayList<>();
    }
}
