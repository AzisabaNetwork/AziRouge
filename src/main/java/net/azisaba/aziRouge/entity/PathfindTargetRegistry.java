package net.azisaba.aziRouge.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PathfindTargetRegistry {
    private static final double MATCH_DISTANCE_SQUARED = 4.0D;
    private static final Map<UUID, Location> TARGETS = new HashMap<>();

    private PathfindTargetRegistry() {
    }

    public static void allow(Entity entity, Location target) {
        if (entity == null || target == null || target.getWorld() == null) {
            return;
        }
        TARGETS.put(entity.getUniqueId(), target.clone());
    }

    public static void clear(Entity entity) {
        if (entity != null) {
            TARGETS.remove(entity.getUniqueId());
        }
    }

    public static void clear(UUID entityId) {
        TARGETS.remove(entityId);
    }

    public static void clearAll() {
        TARGETS.clear();
    }

    public static boolean isAllowed(Entity entity, Location target) {
        if (entity == null || target == null || target.getWorld() == null) {
            return false;
        }

        Location allowed = TARGETS.get(entity.getUniqueId());
        return allowed != null
                && allowed.getWorld() != null
                && allowed.getWorld().equals(target.getWorld())
                && allowed.distanceSquared(target) <= MATCH_DISTANCE_SQUARED;
    }
}
