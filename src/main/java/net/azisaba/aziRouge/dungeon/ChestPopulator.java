package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.math.BlockBox;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

        Block chestBlock = findChestBlock(world, piece.worldBounds(), random);
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
        List<ItemStack> loot = lootTable.roll(piece.depth(), plugin.settings().azirouge().chest(), random);
        List<Integer> slots = availableSlots(inventory.getSize());
        Collections.shuffle(slots, random);
        for (int index = 0; index < loot.size() && index < slots.size(); index++) {
            inventory.setItem(slots.get(index), loot.get(index));
        }
    }

    private Block findChestBlock(World world, BlockBox bounds, Random random) {
        int minX = interiorMin(bounds.minX(), bounds.maxX());
        int maxX = interiorMax(bounds.minX(), bounds.maxX());
        int minZ = interiorMin(bounds.minZ(), bounds.maxZ());
        int maxZ = interiorMax(bounds.minZ(), bounds.maxZ());
        int minY = Math.max(bounds.minY() + 1, world.getMinHeight() + 1);
        int maxY = Math.min(bounds.maxY() - 1, world.getMaxHeight() - 2);

        for (int attempt = 0; attempt < 32; attempt++) {
            int x = randomBetween(minX, maxX, random);
            int z = randomBetween(minZ, maxZ, random);
            for (int y = minY; y <= maxY; y++) {
                Block block = world.getBlockAt(x, y, z);
                if (isValidChestBlock(block)) {
                    return block;
                }
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isValidChestBlock(block)) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private boolean isValidChestBlock(Block block) {
        Block floor = block.getRelative(BlockFace.DOWN);
        Block head = block.getRelative(BlockFace.UP);
        return floor.getType().isSolid() && block.isPassable() && head.isPassable() && block.getLightFromSky() == 0;
    }

    private List<Integer> availableSlots(int inventorySize) {
        List<Integer> slots = new ArrayList<>(inventorySize);
        for (int slot = 0; slot < inventorySize; slot++) {
            slots.add(slot);
        }
        return slots;
    }

    private int randomBetween(int min, int max, Random random) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private int interiorMin(int min, int max) {
        return max - min >= 2 ? min + 1 : min;
    }

    private int interiorMax(int min, int max) {
        return max - min >= 2 ? max - 1 : max;
    }
}
