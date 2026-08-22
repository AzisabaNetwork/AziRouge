package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestPopulator {
    public static final String LOOT_CHEST_ID_KEY = "loot_chest_id";
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

        Block chestBlock = RoomPlacementHelper.findPlacementBlock(world, piece.worldBounds(), random);
        if (chestBlock == null) {
            return;
        }

        chestBlock.setType(Material.CHEST, false);
        if (!(chestBlock.getState() instanceof Chest chest)) {
            plugin.getLogger().warning("Failed to create chest state at "
                    + chestBlock.getX() + "," + chestBlock.getY() + "," + chestBlock.getZ()
                    + " in world " + world.getName());
            return;
        }

        Inventory inventory = chest.getBlockInventory();
        inventory.clear();
        chest.getPersistentDataContainer().set(
                new NamespacedKey(plugin, LOOT_CHEST_ID_KEY),
                PersistentDataType.STRING,
                java.util.UUID.randomUUID().toString()
        );
        List<ItemStack> loot = lootTable.roll(piece.depth(), plugin.settings().azirouge().chest(), random);
        List<Integer> slots = availableSlots(inventory.getSize());
        Collections.shuffle(slots, random);
        for (int index = 0; index < loot.size() && index < slots.size(); index++) {
            inventory.setItem(slots.get(index), loot.get(index));
        }
        chest.update(true, false);
    }

    private List<Integer> availableSlots(int inventorySize) {
        List<Integer> slots = new ArrayList<>(inventorySize);
        for (int slot = 0; slot < inventorySize; slot++) {
            slots.add(slot);
        }
        return slots;
    }
}
