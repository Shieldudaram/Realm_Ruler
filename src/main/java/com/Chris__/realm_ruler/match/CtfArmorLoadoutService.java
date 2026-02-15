package com.Chris__.realm_ruler.match;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

public final class CtfArmorLoadoutService {

    public record EquipResult(boolean success, String message) {
    }

    private record ArmorSnapshot(Map<Short, ItemStack> bySlot) {
    }

    private static final String[] RED_ARMOR_SET = new String[]{
            "Armor_Leather_Red_Head",
            "Armor_Leather_Red_Chest",
            "Armor_Leather_Red_Hands",
            "Armor_Leather_Red_Legs"
    };
    private static final String[] BLUE_ARMOR_SET = new String[]{
            "Armor_Leather_Blue_Head",
            "Armor_Leather_Blue_Chest",
            "Armor_Leather_Blue_Hands",
            "Armor_Leather_Blue_Legs"
    };
    private static final String[] YELLOW_ARMOR_SET = new String[]{
            "Armor_Leather_Yellow_Head",
            "Armor_Leather_Yellow_Chest",
            "Armor_Leather_Yellow_Hands",
            "Armor_Leather_Yellow_Legs"
    };
    private static final String[] WHITE_ARMOR_SET = new String[]{
            "Armor_Leather_White_Head",
            "Armor_Leather_White_Chest",
            "Armor_Leather_White_Hands",
            "Armor_Leather_White_Legs"
    };

    private final Object lock = new Object();
    private final BiFunction<String, Integer, ItemStack> itemStackFactory;
    private final HytaleLogger logger;

    private final Map<CtfMatchService.Team, String[]> armorSetByTeam = new EnumMap<>(CtfMatchService.Team.class);
    private final Map<String, ArmorSnapshot> snapshotByUuid = new LinkedHashMap<>();

    public CtfArmorLoadoutService(BiFunction<String, Integer, ItemStack> itemStackFactory, HytaleLogger logger) {
        this.itemStackFactory = itemStackFactory;
        this.logger = logger;
        armorSetByTeam.put(CtfMatchService.Team.RED, RED_ARMOR_SET);
        armorSetByTeam.put(CtfMatchService.Team.BLUE, BLUE_ARMOR_SET);
        armorSetByTeam.put(CtfMatchService.Team.YELLOW, YELLOW_ARMOR_SET);
        armorSetByTeam.put(CtfMatchService.Team.WHITE, WHITE_ARMOR_SET);
    }

    public boolean canJoinWithCurrentArmor(String uuid, Player player) {
        if (uuid == null || uuid.isBlank() || player == null) return false;
        Inventory inventory = player.getInventory();
        if (inventory == null) return false;
        ItemContainer armor = inventory.getArmor();
        if (armor == null) return false;

        synchronized (lock) {
            if (snapshotByUuid.containsKey(uuid)) return true;
        }

        return isArmorContainerEmpty(armor);
    }

    public EquipResult equipTeamArmor(String uuid, Player player, CtfMatchService.Team team) {
        if (uuid == null || uuid.isBlank() || player == null) {
            return new EquipResult(false, "Player not available.");
        }
        if (team == null) {
            return new EquipResult(false, "Team is not available.");
        }

        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return new EquipResult(false, "Inventory not available.");
        }
        ItemContainer armor = inventory.getArmor();
        if (armor == null) {
            return new EquipResult(false, "Armor inventory not available.");
        }

        String[] teamSet = armorSetByTeam.get(team);
        if (teamSet == null || teamSet.length == 0) {
            return new EquipResult(false, "No armor set configured for " + team.displayName() + ".");
        }

        synchronized (lock) {
            if (!snapshotByUuid.containsKey(uuid)) {
                if (!isArmorContainerEmpty(armor)) {
                    return new EquipResult(false, "Remove your current armor before joining CTF.");
                }
                snapshotByUuid.put(uuid, new ArmorSnapshot(snapshotArmor(armor)));
            }
        }

        clearContainer(armor);
        for (String itemId : teamSet) {
            ItemStack stack = safeCreateItem(itemId);
            if (stack == null) {
                return new EquipResult(false, "Missing team armor item: " + itemId);
            }
            if (!armor.canAddItemStack(stack)) {
                return new EquipResult(false, "Unable to equip team armor.");
            }
            armor.addItemStack(stack);
        }

        player.sendInventory();
        return new EquipResult(true, "Equipped " + team.displayName() + " CTF armor.");
    }

    public void restoreForParticipant(String uuid, Player player) {
        if (uuid == null || uuid.isBlank() || player == null) return;

        ArmorSnapshot snapshot;
        synchronized (lock) {
            snapshot = snapshotByUuid.remove(uuid);
        }
        if (snapshot == null) return;

        Inventory inventory = player.getInventory();
        if (inventory == null) return;
        ItemContainer armor = inventory.getArmor();
        if (armor == null) return;

        clearContainer(armor);

        for (Map.Entry<Short, ItemStack> entry : snapshot.bySlot().entrySet()) {
            short slot = entry.getKey();
            ItemStack stack = copyItemStack(entry.getValue());
            if (stack == null) continue;
            if (slot < 0 || slot >= armor.getCapacity()) {
                armor.addItemStack(stack);
                continue;
            }
            armor.setItemStackForSlot(slot, stack);
        }

        player.sendInventory();
    }

    public void restoreForParticipants(Map<String, Player> playerByUuid, Iterable<String> uuids) {
        if (uuids == null) return;
        for (String uuid : uuids) {
            if (uuid == null || uuid.isBlank()) continue;
            Player player = (playerByUuid == null) ? null : playerByUuid.get(uuid);
            if (player == null) {
                clearSnapshot(uuid);
            } else {
                restoreForParticipant(uuid, player);
            }
        }
    }

    public void clearSnapshot(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        synchronized (lock) {
            snapshotByUuid.remove(uuid);
        }
    }

    private ItemStack safeCreateItem(String itemId) {
        if (itemStackFactory == null || itemId == null || itemId.isBlank()) return null;
        try {
            return itemStackFactory.apply(itemId, 1);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[RR-CTF] Failed to create team armor item=%s", itemId);
            return null;
        }
    }

    private static boolean isArmorContainerEmpty(ItemContainer armor) {
        if (armor == null) return true;

        final boolean[] found = new boolean[]{false};
        armor.forEach((slot, stack) -> {
            if (found[0]) return;
            if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) return;
            found[0] = true;
        });
        return !found[0];
    }

    private static Map<Short, ItemStack> snapshotArmor(ItemContainer armor) {
        Map<Short, ItemStack> out = new LinkedHashMap<>();
        if (armor == null) return out;

        armor.forEach((slot, stack) -> {
            if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) return;
            ItemStack copy = copyItemStack(stack);
            if (copy != null) {
                out.put(slot, copy);
            }
        });

        return out;
    }

    private static void clearContainer(ItemContainer container) {
        if (container == null) return;
        short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) continue;
            container.removeItemStackFromSlot(slot, Math.max(1, stack.getQuantity()));
        }
    }

    private static ItemStack copyItemStack(ItemStack stack) {
        if (stack == null) return null;
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) return null;

        try {
            return new ItemStack(
                    itemId,
                    Math.max(1, stack.getQuantity()),
                    stack.getDurability(),
                    stack.getMaxDurability(),
                    stack.getMetadata()
            );
        } catch (Throwable ignored) {
        }

        try {
            return new ItemStack(itemId, Math.max(1, stack.getQuantity()));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
