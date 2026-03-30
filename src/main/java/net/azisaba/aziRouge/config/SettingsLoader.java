package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class SettingsLoader {
    private SettingsLoader() {
    }

    public static PluginSettings load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        List<String> patterns = new ArrayList<>(config.getStringList("generation.template-patterns"));
        if (patterns.isEmpty()) {
            patterns.add("templates/*.yml");
        }
        int minPieceCount = Math.max(1, config.getInt("generation.algorithm.min-piece-count", 12));
        int maxPieceCount = Math.max(minPieceCount, config.getInt("generation.algorithm.max-piece-count", 24));

        Material doorMaterial = Material.matchMaterial(config.getString("door.material", "SPRUCE_DOOR"));
        if (doorMaterial == null || !doorMaterial.name().endsWith("_DOOR")) {
            plugin.getLogger().warning("Invalid door.material. Falling back to SPRUCE_DOOR");
            doorMaterial = Material.SPRUCE_DOOR;
        }

        return new PluginSettings(
                new GenerationSettings(
                        List.copyOf(patterns),
                        requireText(config.getString("generation.start-piece"), "start_room"),
                        requireText(config.getString("generation.world"), "world"),
                        new IntVector3(
                                config.getInt("generation.origin.x", 0),
                                config.getInt("generation.origin.y", 64),
                                config.getInt("generation.origin.z", 0)
                        ),
                        config.getLong("generation.seed", 123456789L),
                        Math.max(1, config.getInt("generation.algorithm.max-depth", 8)),
                        clamp(config.getDouble("generation.algorithm.branch-chance", 0.45D), 0.0D, 1.0D),
                        Math.max(1, config.getInt("generation.algorithm.target-piece-count", 18)),
                        Math.max(0, config.getInt("generation.algorithm.piece-count-jitter", 2)),
                        minPieceCount,
                        maxPieceCount
                ),
                new DoorSettings(
                        config.getBoolean("door.enabled", true),
                        clamp(config.getDouble("door.chance", 0.35D), 0.0D, 1.0D),
                        doorMaterial
                ),
                new DebugSettings(config.getBoolean("debug.enabled", false)),
                new EnemySettings(
                        config.getBoolean("enemies.enabled", false),
                        requireText(config.getString("enemies.mode"), "reserved")
                )
        );
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
