package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.entity.MobProfile;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                        loadMobSpawnSettings(config),
                        loadChestSettings(plugin, config)
                )
        );
    }

    private static MobSpawnSettings loadMobSpawnSettings(FileConfiguration config) {
        return new MobSpawnSettings(
                Math.max(1L, config.getLong("azirouge.mob-spawn-interval-seconds", 30L)),
                Math.max(1, config.getInt("azirouge.mob-spawn-count-per-interval", 3)),
                Math.max(0, config.getInt("azirouge.mob-spawn-max-alive-power", 48)),
                loadMobProfiles(config.getConfigurationSection("azirouge.mobs"))
        );
    }

    private static Map<String, MobProfileSettings> loadMobProfiles(ConfigurationSection section) {
        Map<String, MobProfileSettings> profiles = new LinkedHashMap<>();
        for (MobProfile profile : MobProfile.values()) {
            MobProfileSettings defaults = profile.defaultSettings();
            ConfigurationSection profileSection = section == null ? null : section.getConfigurationSection(profile.key());
            MobAiSettings defaultAi = defaults.ai();
            MobAiSettings ai = profileSection == null
                    ? defaultAi
                    : loadMobAiSettings(profileSection.getConfigurationSection("ai"), defaultAi);
            List<MobDropEntrySettings> drops = profileSection == null
                    ? defaults.drops()
                    : loadMobDrops(profileSection.getList("drops"), defaults.drops());
            MobProfileSettings settings = profileSection == null
                    ? defaults
                    : new MobProfileSettings(
                            Math.max(0, profileSection.getInt("weight", defaults.weight())),
                            Math.max(0, profileSection.getInt("power", defaults.power())),
                            Math.max(1.0D, profileSection.getDouble("max-health", defaults.maxHealth())),
                            Math.max(0.0D, profileSection.getDouble("movement-speed", defaults.movementSpeed())),
                            Math.max(0.0D, profileSection.getDouble("attack-damage", defaults.attackDamage())),
                            ai,
                            drops
                    );
            profiles.put(profile.key(), settings);
        }
        return Map.copyOf(profiles);
    }

    private static MobAiSettings loadMobAiSettings(ConfigurationSection section, MobAiSettings defaults) {
        if (section == null) {
            return defaults;
        }
        return new MobAiSettings(
                section.getBoolean("enabled", defaults.enabled()),
                Math.max(1L, section.getLong("tick-interval-ticks", defaults.tickIntervalTicks()))
        );
    }

    private static List<MobDropEntrySettings> loadMobDrops(
            List<?> rawEntries,
            List<MobDropEntrySettings> defaults
    ) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            return defaults;
        }

        List<MobDropEntrySettings> entries = new ArrayList<>();
        for (Object rawEntry : rawEntries) {
            MobDropEntrySettings entry = loadMobDropEntry(rawEntry);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries.isEmpty() ? defaults : List.copyOf(entries);
    }

    private static MobDropEntrySettings loadMobDropEntry(Object rawEntry) {
        Map<?, ?> values = asMap(rawEntry);
        if (values == null || values.isEmpty()) {
            return null;
        }

        String material = normalizeRequiredText(stringValue(values.get("material")));
        if (material == null || Material.matchMaterial(material) == null) {
            return null;
        }

        int minAmount = Math.max(1, intValue(values.get("min-amount"), 1));
        int maxAmount = Math.max(minAmount, intValue(values.get("max-amount"), minAmount));
        int minDepth = Math.max(0, intValue(values.get("min-depth"), 0));
        int rawMaxDepth = intValue(values.get("max-depth"), -1);
        int maxDepth = rawMaxDepth < 0 ? Integer.MAX_VALUE : Math.max(minDepth, rawMaxDepth);
        return new MobDropEntrySettings(
                material,
                clamp(doubleValue(values.get("chance"), 1.0D), 0.0D, 1.0D),
                doubleValue(values.get("depth-chance-multiplier"), 0.0D),
                minAmount,
                maxAmount,
                minDepth,
                maxDepth,
                normalizeOptionalText(stringValue(values.get("potion-type"))),
                loadStoredEnchantments(values.get("stored-enchantments"))
        );
    }

    private static ChestSettings loadChestSettings(JavaPlugin plugin, FileConfiguration config) {
        double baseSpawnChance = clamp(config.getDouble("azirouge.chest.base-spawn-chance", 0.1D), 0.0D, 1.0D);
        double depthMultiplier = Math.max(0.0D, config.getDouble("azirouge.chest.depth-multiplier", 0.05D));
        double maxSpawnChance = clamp(
                config.getDouble("azirouge.chest.max-spawn-chance", 0.9D),
                baseSpawnChance,
                1.0D
        );

        ChestLootTierSettings defaultTier1 = defaultTier1();
        ChestLootTierSettings defaultTier2 = defaultTier2();
        ChestLootTierSettings defaultTier3 = defaultTier3();
        ConfigurationSection tiers = config.getConfigurationSection("azirouge.chest.tiers");

        return new ChestSettings(
                baseSpawnChance,
                depthMultiplier,
                maxSpawnChance,
                loadTier(plugin, tiers == null ? null : tiers.getConfigurationSection("tier-1"), defaultTier1, "tier-1"),
                loadTier(plugin, tiers == null ? null : tiers.getConfigurationSection("tier-2"), defaultTier2, "tier-2"),
                loadTier(plugin, tiers == null ? null : tiers.getConfigurationSection("tier-3"), defaultTier3, "tier-3")
        );
    }

    private static ChestLootTierSettings loadTier(
            JavaPlugin plugin,
            ConfigurationSection section,
            ChestLootTierSettings defaults,
            String tierName
    ) {
        if (section == null) {
            return defaults;
        }

        int minRolls = Math.max(0, section.getInt("min-rolls", defaults.minRolls()));
        int maxRolls = Math.max(minRolls, section.getInt("max-rolls", defaults.maxRolls()));
        List<?> rawEntries = section.getList("entries");
        if (rawEntries == null || rawEntries.isEmpty()) {
            return new ChestLootTierSettings(minRolls, maxRolls, defaults.entries());
        }

        List<ChestLootEntrySettings> entries = new ArrayList<>();
        for (Object rawEntry : rawEntries) {
            ChestLootEntrySettings entry = loadEntry(plugin, rawEntry, tierName);
            if (entry != null) {
                entries.add(entry);
            }
        }

        if (entries.isEmpty()) {
            plugin.getLogger().warning("All chest loot entries were invalid for " + tierName + ". Falling back to defaults.");
            return new ChestLootTierSettings(minRolls, maxRolls, defaults.entries());
        }
        return new ChestLootTierSettings(minRolls, maxRolls, List.copyOf(entries));
    }

    private static ChestLootEntrySettings loadEntry(JavaPlugin plugin, Object rawEntry, String tierName) {
        Map<?, ?> values = asMap(rawEntry);
        if (values == null || values.isEmpty()) {
            plugin.getLogger().warning("Ignoring malformed chest loot entry in " + tierName);
            return null;
        }

        String material = normalizeRequiredText(stringValue(values.get("material")));
        if (material == null) {
            plugin.getLogger().warning("Ignoring chest loot entry without material in " + tierName);
            return null;
        }
        if (Material.matchMaterial(material) == null) {
            plugin.getLogger().warning("Ignoring chest loot entry with invalid material '" + material + "' in " + tierName);
            return null;
        }

        int minAmount = Math.max(1, intValue(values.get("min-amount"), 1));
        int maxAmount = Math.max(minAmount, intValue(values.get("max-amount"), minAmount));
        return new ChestLootEntrySettings(
                material,
                Math.max(1, intValue(values.get("weight"), 1)),
                minAmount,
                maxAmount,
                normalizeOptionalText(stringValue(values.get("potion-type"))),
                loadStoredEnchantments(values.get("stored-enchantments"))
        );
    }

    private static Map<String, Integer> loadStoredEnchantments(Object rawValue) {
        Map<?, ?> rawMap = asMap(rawValue);
        if (rawMap == null || rawMap.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = normalizeOptionalText(stringValue(entry.getKey()));
            if (key == null) {
                continue;
            }
            enchantments.put(key.toLowerCase(Locale.ROOT), Math.max(1, intValue(entry.getValue(), 1)));
        }
        return enchantments.isEmpty() ? Map.of() : Map.copyOf(enchantments);
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        if (value instanceof ConfigurationSection section) {
            return section.getValues(false);
        }
        return null;
    }

    private static String normalizeRequiredText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static ChestLootTierSettings defaultTier1() {
        return new ChestLootTierSettings(
                2,
                4,
                List.of(
                        new ChestLootEntrySettings("BREAD", 14, 2, 4, null, Map.of()),
                        new ChestLootEntrySettings("COOKED_BEEF", 12, 2, 4, null, Map.of()),
                        new ChestLootEntrySettings("WOODEN_SWORD", 10, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("WOODEN_AXE", 10, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("LEATHER_HELMET", 8, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("LEATHER_CHESTPLATE", 8, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("TORCH", 6, 8, 16, null, Map.of())
                )
        );
    }

    private static ChestLootTierSettings defaultTier2() {
        return new ChestLootTierSettings(
                3,
                5,
                List.of(
                        new ChestLootEntrySettings("IRON_SWORD", 12, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("IRON_AXE", 11, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("IRON_HELMET", 10, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("IRON_CHESTPLATE", 10, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("POTION", 8, 1, 1, "HEALING", Map.of()),
                        new ChestLootEntrySettings("POTION", 7, 1, 1, "SWIFTNESS", Map.of()),
                        new ChestLootEntrySettings("GOLDEN_APPLE", 6, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("ARROW", 5, 8, 16, null, Map.of())
                )
        );
    }

    private static ChestLootTierSettings defaultTier3() {
        return new ChestLootTierSettings(
                4,
                6,
                List.of(
                        new ChestLootEntrySettings("DIAMOND_SWORD", 11, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("DIAMOND_AXE", 10, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("DIAMOND_HELMET", 9, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("DIAMOND_CHESTPLATE", 9, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("GOLDEN_APPLE", 8, 1, 2, null, Map.of()),
                        new ChestLootEntrySettings("ENCHANTED_BOOK", 7, 1, 1, null, Map.of("sharpness", 3)),
                        new ChestLootEntrySettings("ENCHANTED_BOOK", 7, 1, 1, null, Map.of("protection", 3)),
                        new ChestLootEntrySettings("ENCHANTED_BOOK", 5, 1, 1, null, Map.of("unbreaking", 3)),
                        new ChestLootEntrySettings("POTION", 5, 1, 1, "STRENGTH", Map.of())
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
