package net.azisaba.aziRouge;

import net.azisaba.aziRouge.author.MissingSelectionProvider;
import net.azisaba.aziRouge.author.SelectionProvider;
import net.azisaba.aziRouge.author.TemplateAuthoringService;
import net.azisaba.aziRouge.command.AziRougeCommand;
import net.azisaba.aziRouge.config.PluginSettings;
import net.azisaba.aziRouge.config.SettingsLoader;
import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.dungeon.ChestPopulator;
import net.azisaba.aziRouge.dungeon.DungeonGenerator;
import net.azisaba.aziRouge.dungeon.EnemyPlacementService;
import net.azisaba.aziRouge.dungeon.MiningService;
import net.azisaba.aziRouge.dungeon.TreasurePickupListener;
import net.azisaba.aziRouge.dungeon.TreasurePopulator;
import net.azisaba.aziRouge.dungeon.TrapPopulator;
import net.azisaba.aziRouge.dungeon.TrapTriggerListener;
import net.azisaba.aziRouge.game.EconomyService;
import net.azisaba.aziRouge.game.GameSessionManager;
import net.azisaba.aziRouge.entity.MobAiManager;
import net.azisaba.aziRouge.entity.MobDropListener;
import net.azisaba.aziRouge.entity.MobSpawnManager;
import net.azisaba.aziRouge.game.BossBattleService;
import net.azisaba.aziRouge.game.SessionGameplayService;
import net.azisaba.aziRouge.game.SessionPlayerHealthListener;
import net.azisaba.aziRouge.game.SessionPlayerListener;
import net.azisaba.aziRouge.game.SessionScoreboardService;
import net.azisaba.aziRouge.game.PortalService;
import net.azisaba.aziRouge.game.ShopService;
import net.azisaba.aziRouge.game.GameMenuService;
import net.azisaba.aziRouge.listener.GlobalJoinQuitListener;
import net.azisaba.aziRouge.schematic.MissingSchematicAdapter;
import net.azisaba.aziRouge.schematic.SchematicAdapter;
import net.azisaba.aziRouge.template.TemplateManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AziRouge extends JavaPlugin {
    private PluginSettings settings;
    private DebugLogger debugLogger;
    private TemplateManager templateManager;
    private SchematicAdapter schematicAdapter;
    private SelectionProvider selectionProvider;
    private TemplateAuthoringService templateAuthoringService;
    private DungeonGenerator dungeonGenerator;
    private MobAiManager mobAiManager;
    private MobSpawnManager mobSpawnManager;
    private GameSessionManager gameSessionManager;
    private PortalService portalService;
    private BossBattleService bossBattleService;
    private EconomyService economyService;
    private ShopService shopService;
    private GameMenuService gameMenuService;
    private SessionScoreboardService sessionScoreboardService;
    private SessionGameplayService sessionGameplayService;
    private MiningService miningService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("templates/example-basic.yml", false);
        saveResource("schematics/README.txt", false);
        reloadPluginState();
        gameSessionManager.cleanupLeftoverWorldFoldersOnStartup();
        registerCommands();
        registerListeners();
    }

    @Override
    public void onDisable() {
        if (gameSessionManager != null) {
            gameSessionManager.shutdown();
        }
        if (sessionScoreboardService != null) {
            sessionScoreboardService.shutdown();
        }
        if (sessionGameplayService != null) {
            sessionGameplayService.shutdown();
        }
        if (bossBattleService != null) {
            bossBattleService.shutdown();
        }
        if (miningService != null) {
            miningService.clearAll();
        }
        if (portalService != null) {
            portalService.shutdown();
        }
        if (mobAiManager != null) {
            mobAiManager.shutdown();
        }
        if (gameMenuService != null) {
            gameMenuService.shutdown();
        }
        if (schematicAdapter != null) {
            schematicAdapter.clearCache();
        }
    }

    public void reloadPluginState() {
        reloadConfig();
        this.settings = SettingsLoader.load(this);
        this.debugLogger = new DebugLogger(this, settings.debug().enabled());
        this.templateManager = new TemplateManager(this, debugLogger);
        this.schematicAdapter = createSchematicAdapter();
        this.selectionProvider = createSelectionProvider();
        this.templateAuthoringService = new TemplateAuthoringService(this, selectionProvider, debugLogger);
        this.dungeonGenerator = createDungeonGenerator();
        ensureRuntimeServices();
        getLogger().info("AziRouge reloaded. debug=" + debugLogger.isEnabled()
                + " schematic=" + schematicAdapter.describeAvailability()
                + " selection=" + selectionProvider.describeAvailability());
    }

    private DungeonGenerator createDungeonGenerator() {
        ChestPopulator chestPopulator = new ChestPopulator(this);
        TreasurePopulator treasurePopulator = new TreasurePopulator(this);
        TrapPopulator trapPopulator = new TrapPopulator(this);
        return new DungeonGenerator(
                this,
                debugLogger,
                templateManager,
                schematicAdapter,
                new EnemyPlacementService(debugLogger),
                chestPopulator,
                treasurePopulator,
                trapPopulator,
                miningService
        );
    }

    private void ensureRuntimeServices() {
        if (mobAiManager == null) {
            this.mobAiManager = new MobAiManager(this);
        }
        if (mobSpawnManager == null) {
            this.mobSpawnManager = new MobSpawnManager(this, mobAiManager);
        }
        if (gameSessionManager == null) {
            this.gameSessionManager = new GameSessionManager(this, mobSpawnManager);
        }
        if (economyService == null) {
            this.economyService = new EconomyService(this);
        }
        if (portalService == null) {
            this.portalService = new PortalService(this, gameSessionManager);
        }
        if (bossBattleService == null) {
            this.bossBattleService = new BossBattleService(this, gameSessionManager);
        }
        if (shopService == null) {
            this.shopService = new ShopService(this, gameSessionManager);
        }
        if (gameMenuService == null) {
            this.gameMenuService = new GameMenuService(this);
        }
        if (sessionScoreboardService == null) {
            this.sessionScoreboardService = new SessionScoreboardService(this, gameSessionManager);
        }
        if (sessionGameplayService == null) {
            this.sessionGameplayService = new SessionGameplayService(this, gameSessionManager);
        }
        if (miningService == null) {
            this.miningService = new MiningService(this);
            this.dungeonGenerator = createDungeonGenerator();
        }
        gameMenuService.refresh();
        sessionScoreboardService.start();
        sessionGameplayService.start();
        bossBattleService.start();
    }

    public PluginSettings settings() {
        return settings;
    }

    public DungeonGenerator dungeonGenerator() {
        return dungeonGenerator;
    }

    public GameSessionManager gameSessionManager() {
        return gameSessionManager;
    }

    public PortalService portalService() {
        return portalService;
    }

    public BossBattleService bossBattleService() {
        return bossBattleService;
    }

    public EconomyService economyService() {
        return economyService;
    }

    public ShopService shopService() {
        return shopService;
    }

    public MiningService miningService() {
        return miningService;
    }

    public TemplateAuthoringService templateAuthoringService() {
        return templateAuthoringService;
    }

    public DebugLogger debugLogger() {
        return debugLogger;
    }

    public void setDebugEnabled(boolean enabled) {
        debugLogger.setEnabled(enabled);
    }

    private void registerCommands() {
        PluginCommand command = getCommand("azirouge");
        if (command == null) {
            throw new IllegalStateException("Command azirouge is not defined in plugin.yml");
        }
        AziRougeCommand executor = new AziRougeCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(mobAiManager, this);
        getServer().getPluginManager().registerEvents(new MobDropListener(this, gameSessionManager), this);
        getServer().getPluginManager().registerEvents(new TreasurePickupListener(this), this);
        getServer().getPluginManager().registerEvents(new TrapTriggerListener(this), this);
        getServer().getPluginManager().registerEvents(new SessionPlayerHealthListener(gameSessionManager), this);
        getServer().getPluginManager().registerEvents(new SessionPlayerListener(gameSessionManager), this);
        getServer().getPluginManager().registerEvents(portalService, this);
        getServer().getPluginManager().registerEvents(bossBattleService, this);
        getServer().getPluginManager().registerEvents(shopService, this);
        getServer().getPluginManager().registerEvents(gameMenuService, this);
        getServer().getPluginManager().registerEvents(sessionGameplayService, this);
        getServer().getPluginManager().registerEvents(miningService, this);
        getServer().getPluginManager().registerEvents(new GlobalJoinQuitListener(this), this);
    }

    private SchematicAdapter createSchematicAdapter() {
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            getLogger().warning("WorldEdit is not installed. schematic placement is unavailable.");
            return new MissingSchematicAdapter("WorldEdit plugin not found");
        }
        try {
            Class<?> type = Class.forName("net.azisaba.aziRouge.schematic.WorldEditSchematicAdapter");
            return (SchematicAdapter) type
                    .getConstructor(JavaPlugin.class, DebugLogger.class)
                    .newInstance(this, debugLogger);
        } catch (ReflectiveOperationException | LinkageError ex) {
            getLogger().severe("Failed to initialize WorldEdit adapter: " + ex.getMessage());
            return new MissingSchematicAdapter("WorldEdit adapter initialization failed");
        }
    }

    private SelectionProvider createSelectionProvider() {
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            return new MissingSelectionProvider("WorldEdit plugin not found");
        }
        try {
            Class<?> type = Class.forName("net.azisaba.aziRouge.author.WorldEditSelectionProvider");
            return (SelectionProvider) type
                    .getConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError ex) {
            getLogger().severe("Failed to initialize WorldEdit selection provider: " + ex.getMessage());
            return new MissingSelectionProvider("WorldEdit selection provider initialization failed");
        }
    }
}
