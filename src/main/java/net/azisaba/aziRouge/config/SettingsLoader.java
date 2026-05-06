package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.entity.MobProfile;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
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
        int legacyMinPieceCount = Math.max(1, config.getInt("generation.algorithm.min-piece-count", 12));
        int legacyMaxPieceCount = Math.max(legacyMinPieceCount, config.getInt("generation.algorithm.max-piece-count", 24));
        int configuredMaxDepth = Math.max(1, config.getInt("generation.algorithm.max-depth", 8));
        int minPiecesPerDepth = Math.max(1, config.getInt(
                "generation.algorithm.min-pieces-per-depth",
                Math.max(1, legacyMinPieceCount / configuredMaxDepth)
        ));
        int maxPiecesPerDepth = Math.max(minPiecesPerDepth, config.getInt(
                "generation.algorithm.max-pieces-per-depth",
                Math.max(minPiecesPerDepth, legacyMaxPieceCount / configuredMaxDepth)
        ));

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
                        configuredMaxDepth,
                        clamp(config.getDouble("generation.algorithm.branch-chance", 0.45D), 0.0D, 1.0D),
                        clamp(config.getDouble("generation.algorithm.entrance-branch-bonus", 0.15D), 0.0D, 1.0D),
                        Math.max(0.1D, config.getDouble("generation.algorithm.depth-prediction-multiplier", 1.0D)),
                        minPiecesPerDepth,
                        maxPiecesPerDepth,
                        Math.max(0.0D, config.getDouble("generation.algorithm.adjacent-piece-penalty", 2.0D))
                ),
                new DoorSettings(
                        config.getBoolean("door.enabled", true),
                        clamp(config.getDouble("door.chance", 0.35D), 0.0D, 1.0D),
                        doorMaterial
                ),
                new DebugSettings(config.getBoolean("debug.enabled", false)),
                loadSessionSettings(plugin, config),
                loadHomeSettings(config),
                loadDungeonSettings(config),
                loadPortalSettings(config),
                loadEconomySettings(plugin, config),
                loadShopSettings(plugin, config),
                loadGuiSettings(config),
                loadPlayerSettings(config),
                new EnemySettings(
                        config.getBoolean("enemies.enabled", false),
                        requireText(config.getString("enemies.mode"), "reserved")
                ),
                new AziRougeSettings(
                        loadMobSpawnSettings(config),
                        loadChestSettings(plugin, config),
                        loadTreasureSettings(plugin, config),
                        loadTrapSettings(config)
                ),
                new JoinSettings(
                        config.getBoolean("join.isbeta", false)
                )
        );
    }

    private static SessionSettings loadSessionSettings(JavaPlugin plugin, FileConfiguration config) {
        int maxMaxPlayers = Math.max(1, config.getInt("sessions.max-max-players", 8));
        int defaultMaxPlayers = clampInt(config.getInt("sessions.default-max-players", 4), 1, maxMaxPlayers);
        return new SessionSettings(
                defaultMaxPlayers,
                maxMaxPlayers,
                Math.max(1, config.getInt("sessions.idle-timeout-seconds", 60)),
                requireText(config.getString("sessions.world-name-prefix"), "azirouge_"),
                resolvePath(plugin, requireText(config.getString("sessions.home-template-world-path"), "azirouge_home_template")),
                config.getBoolean("sessions.cleanup-leftover-worlds-on-startup", true)
        );
    }

    private static HomeSettings loadHomeSettings(FileConfiguration config) {
        IntVector3 spawn = new IntVector3(
                config.getInt("home.spawn.x", 0),
                config.getInt("home.spawn.y", 64),
                config.getInt("home.spawn.z", 0)
        );
        IntVector3 returnSpawn = new IntVector3(
                config.getInt("home.return-spawn.x", spawn.x()),
                config.getInt("home.return-spawn.y", spawn.y()),
                config.getInt("home.return-spawn.z", spawn.z())
        );
        IntVector3 min = new IntVector3(
                config.getInt("home.area.min.x", -16),
                config.getInt("home.area.min.y", 0),
                config.getInt("home.area.min.z", -16)
        );
        IntVector3 max = new IntVector3(
                config.getInt("home.area.max.x", 16),
                config.getInt("home.area.max.y", 255),
                config.getInt("home.area.max.z", 16)
        );
        return new HomeSettings(spawn, returnSpawn, BlockBox.fromPoints(min, max));
    }

    private static DungeonSettings loadDungeonSettings(FileConfiguration config) {
        return new DungeonSettings(
                Math.max(1, config.getInt("dungeon.base-distance-from-home", 512)),
                Math.max(1, config.getInt("dungeon.round-spacing", 512)),
                Math.max(1, config.getInt("dungeon.default-max-depth", config.getInt("generation.algorithm.max-depth", 8)))
        );
    }

    private static ShopSettings loadShopSettings(JavaPlugin plugin, FileConfiguration config) {
        return new ShopSettings(
                requireText(config.getString("shop.title"), "AziRouge Shop"),
                loadShopTrades(plugin, config.getList("shop.trades.in-round"), "shop.trades.in-round"),
                loadShopTrades(plugin, config.getList("shop.trades.between-round"), "shop.trades.between-round")
        );
    }

    private static GuiSettings loadGuiSettings(FileConfiguration config) {
        int maxDepth = Math.max(1, config.getInt("gui.depth.max", config.getInt("dungeon.default-max-depth", 8)));
        int defaultDepth = clampInt(config.getInt("gui.depth.default", config.getInt("dungeon.default-max-depth", 8)), 1, maxDepth);
        return new GuiSettings(
                maxDepth,
                defaultDepth
        );
    }

    private static PlayerSettings loadPlayerSettings(FileConfiguration config) {
        return new PlayerSettings(
                Math.max(0.0D, config.getDouble("player.sprint-stamina.drain-per-second", 1.0D)),
                Math.max(0.0D, config.getDouble("player.sprint-stamina.recovery-per-second", 1.5D))
        );
    }

    private static List<ShopTradeSettings> loadShopTrades(JavaPlugin plugin, List<?> rawTrades, String path) {
        if (rawTrades == null || rawTrades.isEmpty()) {
            return List.of();
        }

        List<ShopTradeSettings> trades = new ArrayList<>();
        for (Object rawTrade : rawTrades) {
            Map<?, ?> values = asMap(rawTrade);
            if (values == null || values.isEmpty()) {
                plugin.getLogger().warning("Ignoring malformed shop trade in " + path);
                continue;
            }

            String id = normalizeOptionalText(stringValue(values.get("id")));
            String materialName = normalizeRequiredText(stringValue(values.get("material")));
            Material material = materialName == null ? null : Material.matchMaterial(materialName);
            long price = longValue(values.get("price"), -1L);
            if (id == null || material == null || !material.isItem() || material.isAir() || price < 0L) {
                plugin.getLogger().warning("Ignoring invalid shop trade in " + path + ": " + values);
                continue;
            }

            int amount = clampInt(intValue(values.get("amount"), 1), 1, material.getMaxStackSize());
            trades.add(new ShopTradeSettings(id.toLowerCase(Locale.ROOT), material, amount, price));
        }
        return List.copyOf(trades);
    }

    private static EconomySettings loadEconomySettings(JavaPlugin plugin, FileConfiguration config) {
        Map<Material, Long> sellPrices = new LinkedHashMap<>();
        ConfigurationSection prices = config.getConfigurationSection("economy.sell-prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                long price = prices.getLong(key, 0L);
                if (material == null || price <= 0L) {
                    plugin.getLogger().warning("Ignoring invalid economy sell price: " + key);
                    continue;
                }
                sellPrices.put(material, price);
            }
        }

        return new EconomySettings(
                Math.max(0L, config.getLong("economy.initial-balance", 0L)),
                new EconomyMaintenanceSettings(
                        Math.max(0L, config.getLong("economy.maintenance.base", 0L)),
                        Math.max(0L, config.getLong("economy.maintenance.per-round", 0L)),
                        Math.max(0.0D, config.getDouble("economy.maintenance.multiplier", 1.0D))
                ),
                Map.copyOf(sellPrices)
        );
    }

    private static PortalSettings loadPortalSettings(FileConfiguration config) {
        BlockBox homeToDungeonArea = loadBlockBox(
                config,
                "portals.home-to-dungeon.area",
                new IntVector3(-1, 64, 2),
                new IntVector3(1, 64, 2)
        );
        IntVector3 destinationOffset = new IntVector3(
                config.getInt("portals.home-to-dungeon.destination-offset.x", 0),
                config.getInt("portals.home-to-dungeon.destination-offset.y", 64),
                config.getInt("portals.home-to-dungeon.destination-offset.z", 3)
        );
        float dungeonDestinationYawOffset = (float) config.getDouble("portals.home-to-dungeon.destination-offset.yaw", 0.0D);
        BlockBox dungeonToHomeArea = loadBlockBox(
                config,
                "portals.dungeon-to-home.area",
                new IntVector3(-1, 64, -1),
                new IntVector3(1, 66, 1)
        );
        float homeDestinationYawOffset = (float) config.getDouble("portals.dungeon-to-home.destination-yaw-offset", 0.0D);

        return new PortalSettings(
                new PortalHomeToDungeonSettings(homeToDungeonArea, destinationOffset, dungeonDestinationYawOffset),
                new PortalDungeonToHomeSettings(dungeonToHomeArea, homeDestinationYawOffset),
                Math.max(1, config.getInt("portals.cooldown-seconds", 3))
        );
    }

    private static BlockBox loadBlockBox(FileConfiguration config, String path, IntVector3 defaultMin, IntVector3 defaultMax) {
        IntVector3 min = new IntVector3(
                config.getInt(path + ".min.x", defaultMin.x()),
                config.getInt(path + ".min.y", defaultMin.y()),
                config.getInt(path + ".min.z", defaultMin.z())
        );
        IntVector3 max = new IntVector3(
                config.getInt(path + ".max.x", defaultMax.x()),
                config.getInt(path + ".max.y", defaultMax.y()),
                config.getInt(path + ".max.z", defaultMax.z())
        );
        return BlockBox.fromPoints(min, max);
    }

    private static Path resolvePath(JavaPlugin plugin, String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return plugin.getServer().getWorldContainer().toPath().resolve(path).toAbsolutePath().normalize();
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

    private static TreasureSettings loadTreasureSettings(JavaPlugin plugin, FileConfiguration config) {
        double baseSpawnChance = clamp(config.getDouble("azirouge.treasure.base-spawn-chance", 0.08D), 0.0D, 1.0D);
        double depthMultiplier = Math.max(0.0D, config.getDouble("azirouge.treasure.depth-multiplier", 0.04D));
        double maxSpawnChance = clamp(
                config.getDouble("azirouge.treasure.max-spawn-chance", 0.65D),
                baseSpawnChance,
                1.0D
        );

        ConfigurationSection tiers = config.getConfigurationSection("azirouge.treasure.tiers");
        ChestLootTierSettings defaultTier1 = defaultTreasureTier1();
        ChestLootTierSettings defaultTier2 = defaultTreasureTier2();
        ChestLootTierSettings defaultTier3 = defaultTreasureTier3();
        return new TreasureSettings(
                baseSpawnChance,
                depthMultiplier,
                maxSpawnChance,
                loadTier(plugin, tiers == null ? null : tiers.getConfigurationSection("tier-1"), defaultTier1, "treasure.tier-1"),
                loadTier(plugin, tiers == null ? null : tiers.getConfigurationSection("tier-2"), defaultTier2, "treasure.tier-2"),
                loadTier(plugin, tiers == null ? null : tiers.getConfigurationSection("tier-3"), defaultTier3, "treasure.tier-3")
        );
    }

    private static TrapSettings loadTrapSettings(FileConfiguration config) {
        double baseSpawnChance = clamp(config.getDouble("azirouge.traps.base-spawn-chance", 0.06D), 0.0D, 1.0D);
        double depthMultiplier = Math.max(0.0D, config.getDouble("azirouge.traps.depth-multiplier", 0.03D));
        double maxSpawnChance = clamp(
                config.getDouble("azirouge.traps.max-spawn-chance", 0.35D),
                baseSpawnChance,
                1.0D
        );
        return new TrapSettings(
                baseSpawnChance,
                depthMultiplier,
                maxSpawnChance,
                loadTrapDefinitions(config.getList("azirouge.traps.definitions"))
        );
    }

    private static List<TrapDefinitionSettings> loadTrapDefinitions(List<?> rawDefinitions) {
        List<TrapDefinitionSettings> defaults = defaultTrapDefinitions();
        if (rawDefinitions == null || rawDefinitions.isEmpty()) {
            return defaults;
        }

        List<TrapDefinitionSettings> definitions = new ArrayList<>();
        for (Object rawDefinition : rawDefinitions) {
            TrapDefinitionSettings definition = loadTrapDefinition(rawDefinition);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        return definitions.isEmpty() ? defaults : List.copyOf(definitions);
    }

    private static TrapDefinitionSettings loadTrapDefinition(Object rawDefinition) {
        Map<?, ?> values = asMap(rawDefinition);
        if (values == null || values.isEmpty()) {
            return null;
        }

        String key = normalizeOptionalText(stringValue(values.get("key")));
        String material = normalizeRequiredText(stringValue(values.get("display-material")));
        if (key == null || material == null) {
            return null;
        }

        Material blockMaterial = Material.matchMaterial(material);
        if (blockMaterial == null || !blockMaterial.isBlock()) {
            return null;
        }

        int minDepth = Math.max(0, intValue(values.get("min-depth"), 0));
        int rawMaxDepth = intValue(values.get("max-depth"), -1);
        int maxDepth = rawMaxDepth < 0 ? Integer.MAX_VALUE : Math.max(minDepth, rawMaxDepth);
        return new TrapDefinitionSettings(
                key.toLowerCase(Locale.ROOT),
                Math.max(0, intValue(values.get("weight"), 1)),
                material,
                Math.max(0.1D, doubleValue(values.get("trigger-radius"), 0.9D)),
                (float) Math.max(0.1D, doubleValue(values.get("explosion-power"), 2.5D)),
                booleanValue(values.get("set-fire"), false),
                booleanValue(values.get("break-blocks"), false),
                minDepth,
                maxDepth
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

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text.trim());
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

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(text.trim());
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

    private static ChestLootTierSettings defaultTreasureTier1() {
        return new ChestLootTierSettings(
                1,
                1,
                List.of(
                        new ChestLootEntrySettings("IRON_INGOT", 12, 1, 2, null, Map.of()),
                        new ChestLootEntrySettings("GOLD_NUGGET", 11, 4, 8, null, Map.of()),
                        new ChestLootEntrySettings("LAPIS_LAZULI", 9, 3, 6, null, Map.of()),
                        new ChestLootEntrySettings("AMETHYST_SHARD", 7, 2, 5, null, Map.of())
                )
        );
    }

    private static ChestLootTierSettings defaultTreasureTier2() {
        return new ChestLootTierSettings(
                1,
                1,
                List.of(
                        new ChestLootEntrySettings("GOLD_INGOT", 11, 1, 2, null, Map.of()),
                        new ChestLootEntrySettings("EMERALD", 10, 1, 2, null, Map.of()),
                        new ChestLootEntrySettings("ENDER_PEARL", 7, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("POTION", 6, 1, 1, "INVISIBILITY", Map.of())
                )
        );
    }

    private static ChestLootTierSettings defaultTreasureTier3() {
        return new ChestLootTierSettings(
                1,
                1,
                List.of(
                        new ChestLootEntrySettings("DIAMOND", 10, 1, 2, null, Map.of()),
                        new ChestLootEntrySettings("NETHERITE_SCRAP", 4, 1, 1, null, Map.of()),
                        new ChestLootEntrySettings("ENCHANTED_BOOK", 7, 1, 1, null, Map.of("fortune", 3)),
                        new ChestLootEntrySettings("GOLDEN_APPLE", 8, 1, 1, null, Map.of())
                )
        );
    }

    private static List<TrapDefinitionSettings> defaultTrapDefinitions() {
        return List.of(
                new TrapDefinitionSettings("landmine", 10, "HEAVY_WEIGHTED_PRESSURE_PLATE", 0.9D, 2.5F, false, false, 0, Integer.MAX_VALUE),
                new TrapDefinitionSettings("ember_mine", 5, "LIGHT_WEIGHTED_PRESSURE_PLATE", 0.85D, 1.75F, true, false, 4, Integer.MAX_VALUE)
        );
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
