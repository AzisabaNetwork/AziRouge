package net.azisaba.aziRouge.game;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;

public final class PlayerInventorySupport {
    private PlayerInventorySupport() {
    }

    static boolean canFit(PlayerInventory inventory, ItemStack candidate) {
        int remaining = candidate.getAmount();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                return true;
            }
            if (item.isSimilar(candidate)) {
                remaining -= Math.max(0, item.getMaxStackSize() - item.getAmount());
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean canFitHotbar(PlayerInventory inventory, ItemStack candidate) {
        int remaining = candidate.getAmount();
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return true;
            }
            if (item.isSimilar(candidate)) {
                remaining -= Math.max(0, item.getMaxStackSize() - item.getAmount());
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void addToHotbar(PlayerInventory inventory, ItemStack candidate) {
        int remaining = candidate.getAmount();
        for (int slot = 0; slot <= 8 && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                ItemStack placed = candidate.clone();
                placed.setAmount(Math.min(remaining, candidate.getMaxStackSize()));
                inventory.setItem(slot, placed);
                remaining -= placed.getAmount();
                continue;
            }
            if (item.isSimilar(candidate)) {
                int addable = Math.min(remaining, item.getMaxStackSize() - item.getAmount());
                if (addable > 0) {
                    item.setAmount(item.getAmount() + addable);
                    remaining -= addable;
                }
            }
        }
        if (remaining > 0) {
            throw new IllegalStateException("item does not fit in hotbar");
        }
    }

    static SaleResult sellPricedStorageContents(PlayerInventory inventory, Map<Material, Long> prices) {
        ItemStack[] contents = inventory.getStorageContents();
        long totalAmount = 0L;
        int totalItems = 0;
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (item == null || item.getType().isAir()) {
                continue;
            }

            Long unitPrice = prices.get(item.getType());
            if (unitPrice == null) {
                continue;
            }

            int amount = item.getAmount();
            totalAmount += unitPrice * amount;
            totalItems += amount;
            contents[index] = null;
        }
        inventory.setStorageContents(contents);
        return new SaleResult(totalAmount, totalItems);
    }

    record SaleResult(long totalAmount, int itemCount) {
    }
}
