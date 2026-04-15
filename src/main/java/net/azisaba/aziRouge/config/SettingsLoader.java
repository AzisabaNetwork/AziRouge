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
                        requireText(config.getString("generation.start-piece"), "root"),
                        requireText(config.getString("generation.world"), "world"),
                        new IntVector3(
                                config.getInt("generation.origin.x", 0),
                                config.getInt("generation.origin.y", 64),
                                config.getInt("generation.origin.z", 0)
                        ),
                        config.getLong("generation.seed", 123456789L),
                        Math.max(1, config.getInt("generation.algorithm.max-depth", 8)),
                        clamp(config.getDouble("generation.algorithm.branch-chance", 0.45D), 0.0D, 1.0D),
                        clamp(config.getDouble("generation.algorithm.entrance-branch-bonus", 0.15D), 0.0D, 1.0D),
                        Math.max(0.1D, config.getDouble("generation.algorithm.depth-prediction-multiplier", 1.0D)),
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
                ),
                new AziRougeSettings(
                        new MobSpawnSettings(
                                Math.max(1L, config.getLong("azirouge.mob-spawn-interval-seconds", 30L)),
                                Math.max(1, config.getInt("azirouge.mob-spawn-count-per-interval", 3))
                        ),
                        new ChestSettings(
                                clamp(config.getDouble("azirouge.chest.base-spawn-chance", 0.1D), 0.0D, 1.0D),
                                Math.max(0.0D, config.getDouble("azirouge.chest.depth-multiplier", 0.05D)),
                                clamp(
                                        config.getDouble("azirouge.chest.max-spawn-chance", 0.9D),
                                        clamp(config.getDouble("azirouge.chest.base-spawn-chance", 0.1D), 0.0D, 1.0D),
                                        1.0D
                                )
                        )
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
