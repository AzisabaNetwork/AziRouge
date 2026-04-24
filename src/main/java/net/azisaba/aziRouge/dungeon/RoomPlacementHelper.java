package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.math.BlockBox;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;

import java.util.Random;

public final class RoomPlacementHelper {
    private RoomPlacementHelper() {
    }

    public static Block findPlacementBlock(World world, BlockBox bounds, Random random) {
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
                if (isValidPlacementBlock(block)) {
                    return block;
                }
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isValidPlacementBlock(block)) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isValidPlacementBlock(Block block) {
        Block floor = block.getRelative(BlockFace.DOWN);
        Block head = block.getRelative(BlockFace.UP);
        return floor.getType().isSolid()
                && block.isPassable()
                && head.isPassable()
                && block.getLightFromSky() == 0
                && !hasDisplayEntityNearby(block);
    }

    private static boolean hasDisplayEntityNearby(Block block) {
        Location center = new Location(block.getWorld(), block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
        for (Entity entity : block.getWorld().getNearbyEntities(center, 0.5D, 1.0D, 0.5D)) {
            if (entity instanceof Display || entity instanceof Interaction) {
                return true;
            }
        }
        return false;
    }

    private static int randomBetween(int min, int max, Random random) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private static int interiorMin(int min, int max) {
        return max - min >= 2 ? min + 1 : min;
    }

    private static int interiorMax(int min, int max) {
        return max - min >= 2 ? max - 1 : max;
    }
}
