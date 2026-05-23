package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestPopulator {
    private final AziRouge plugin;
    private final LootTable lootTable = new LootTable();

    public ChestPopulator(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void populateRoom(World world, PlacedPiece piece) {
        Random random = ThreadLocalRandom.current();
        if (random.nextDouble() > plugin.settings().azirouge().chest().spawnChance(piece.depth())) {
            return;
        }
        populateGuaranteedRoom(world, piece, random);
    }

    public boolean populateGuaranteedRoom(World world, PlacedPiece piece, Random random) {
        Block chestBlock = RoomPlacementHelper.findPlacementBlock(world, piece.worldBounds(), random);
        if (chestBlock == null) {
            return false;
        }

        chestBlock.setType(Material.CHEST, false);
        if (!(chestBlock.getState() instanceof Chest chest)) {
            plugin.getLogger().warning("Failed to create chest state at "
                    + chestBlock.getX() + "," + chestBlock.getY() + "," + chestBlock.getZ()
                    + " in world " + world.getName());
            return false;
        }

        Inventory inventory = chest.getBlockInventory();
        inventory.clear();
        List<ItemStack> loot = lootTable.roll(piece.depth(), plugin.settings().azirouge().chest(), random);
        List<Integer> slots = availableSlots(inventory.getSize());
        Collections.shuffle(slots, random);
        for (int index = 0; index < loot.size() && index < slots.size(); index++) {
            inventory.setItem(slots.get(index), loot.get(index));
        }
        return true;
    }

    private List<Integer> availableSlots(int inventorySize) {
        List<Integer> slots = new ArrayList<>(inventorySize);
        for (int slot = 0; slot < inventorySize; slot++) {
            slots.add(slot);
        }
        return slots;
    }
}
