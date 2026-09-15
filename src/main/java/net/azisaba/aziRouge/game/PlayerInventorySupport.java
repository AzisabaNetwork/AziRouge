package net.azisaba.aziRouge.game;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PlayerInventorySupport {
    private PlayerInventorySupport() {
    }

    static boolean canFit(PlayerInventory inventory, ItemStack candidate) {
        return canFit(inventory, candidate, 0, inventory.getStorageContents().length);
    }

    public static boolean canFitHotbar(PlayerInventory inventory, ItemStack candidate) {
        return canFit(inventory, candidate, 0, 9);
    }

    private static boolean canFit(PlayerInventory inventory, ItemStack candidate, int fromInclusive, int toExclusive) {
        int remaining = candidate.getAmount();
        for (int slot = fromInclusive; slot < toExclusive; slot++) {
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

}
