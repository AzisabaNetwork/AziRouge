package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.dungeon.PlacedPiece;
import net.azisaba.aziRouge.math.BlockBox;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class GameSession {
    private final World world;
    private final Map<UUID, Location> savedLocations = new HashMap<>();
    private final List<PlacedPiece> placedPieces;
    private final Location spawnLocation;
    private final Instant startedAt;
    private BukkitTask mobSpawnTask;

    public GameSession(World world, Location spawnLocation, List<PlacedPiece> placedPieces) {
        this.world = world;
        this.spawnLocation = spawnLocation.clone();
        this.placedPieces = List.copyOf(placedPieces);
        this.startedAt = Instant.now();
    }

    public World world() {
        return world;
    }

    public Map<UUID, Location> savedLocations() {
        return Collections.unmodifiableMap(savedLocations);
    }

    public BukkitTask mobSpawnTask() {
        return mobSpawnTask;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Location spawnLocation() {
        return spawnLocation.clone();
    }

    public List<PlacedPiece> placedPieces() {
        return placedPieces;
    }

    public void setMobSpawnTask(BukkitTask mobSpawnTask) {
        this.mobSpawnTask = mobSpawnTask;
    }

    public void saveLocation(UUID playerId, Location location) {
        if (location != null) {
            savedLocations.put(playerId, location.clone());
        }
    }

    public Location savedLocation(UUID playerId) {
        Location location = savedLocations.get(playerId);
        return location == null ? null : location.clone();
    }

    public void removeSavedLocation(UUID playerId) {
        savedLocations.remove(playerId);
    }

    public PlacedPiece randomRoom(Random random) {
        return placedPieces.get(random.nextInt(placedPieces.size()));
    }

    public int resolveDepth(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        PlacedPiece nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PlacedPiece piece : placedPieces) {
            BlockBox bounds = piece.worldBounds();
            if (bounds.contains(x, y, z)) {
                return piece.depth();
            }

            double distance = distanceSquaredToBox(x, y, z, bounds);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = piece;
            }
        }
        return nearest == null ? 0 : nearest.depth();
    }

    private double distanceSquaredToBox(int x, int y, int z, BlockBox bounds) {
        int dx = axisDistance(x, bounds.minX(), bounds.maxX());
        int dy = axisDistance(y, bounds.minY(), bounds.maxY());
        int dz = axisDistance(z, bounds.minZ(), bounds.maxZ());
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private int axisDistance(int value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0;
    }
}
