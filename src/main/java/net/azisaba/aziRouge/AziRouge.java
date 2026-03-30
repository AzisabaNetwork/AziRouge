package net.azisaba.aziRouge;

import net.azisaba.aziRouge.author.MissingSelectionProvider;
import net.azisaba.aziRouge.author.SelectionProvider;
import net.azisaba.aziRouge.author.TemplateAuthoringService;
import net.azisaba.aziRouge.command.AziRougeCommand;
import net.azisaba.aziRouge.config.PluginSettings;
import net.azisaba.aziRouge.config.SettingsLoader;
import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.dungeon.DungeonGenerator;
import net.azisaba.aziRouge.dungeon.EnemyPlacementService;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("templates/example-basic.yml", false);
        saveResource("schematics/README.txt", false);
        reloadPluginState();
        registerCommands();
    }

    @Override
    public void onDisable() {
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
        this.dungeonGenerator = new DungeonGenerator(
                this,
                debugLogger,
                templateManager,
                schematicAdapter,
                new EnemyPlacementService(debugLogger)
        );
        getLogger().info("AziRouge reloaded. debug=" + debugLogger.isEnabled()
                + " schematic=" + schematicAdapter.describeAvailability()
                + " selection=" + selectionProvider.describeAvailability());
    }

    public PluginSettings settings() {
        return settings;
    }

    public DungeonGenerator dungeonGenerator() {
        return dungeonGenerator;
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
