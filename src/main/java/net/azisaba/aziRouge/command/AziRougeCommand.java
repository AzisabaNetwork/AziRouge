package net.azisaba.aziRouge.command;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.GenerationSettings;
import net.azisaba.aziRouge.dungeon.DungeonGenerationResult;
import net.azisaba.aziRouge.dungeon.GenerationExecutionRequest;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.schematic.SchematicPlacementException;
import net.azisaba.aziRouge.template.TemplateLoadException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class AziRougeCommand implements CommandExecutor, TabCompleter {
    private final AziRouge plugin;

    public AziRougeCommand(AziRouge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /" + label + " <generate|reload|debug>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "generate" -> handleGenerate(sender, Arrays.copyOfRange(args, 1, args.length));
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                sender.sendMessage("Unknown subcommand: " + args[0]);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("generate", "reload", "debug").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length >= 2 && "generate".equalsIgnoreCase(args[0])) {
            List<String> options = List.of("--patterns=", "--start=", "--world=", "--x=", "--y=", "--z=", "--seed=");
            return options.stream().filter(option -> option.startsWith(args[args.length - 1])).toList();
        }
        if (args.length == 2 && "debug".equalsIgnoreCase(args[0])) {
            return List.of("on", "off").stream().filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private boolean handleGenerate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.generate")) {
            sender.sendMessage("You do not have permission to generate dungeons.");
            return true;
        }

        GenerationSettings defaults = plugin.settings().generation();
        Map<String, String> options = parseOptions(args);
        List<String> patterns = options.containsKey("patterns")
                ? Arrays.stream(options.get("patterns").split(","))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new))
                : defaults.templatePatterns();
        String start = options.getOrDefault("start", defaults.startPieceId());
        String worldName = options.getOrDefault("world", defaults.worldName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("World not found: " + worldName);
            return true;
        }

        IntVector3 origin = new IntVector3(
                parseInt(options.get("x"), defaults.origin().x()),
                parseInt(options.get("y"), defaults.origin().y()),
                parseInt(options.get("z"), defaults.origin().z())
        );
        long seed = parseLong(options.get("seed"), defaults.defaultSeed());

        try {
            DungeonGenerationResult result = plugin.dungeonGenerator().generate(
                    new GenerationExecutionRequest(patterns, start, world, origin, seed),
                    plugin.settings()
            );
            sender.sendMessage("Generated dungeon seed=" + result.seed()
                    + " pieces=" + result.placedPieceCount() + "/" + result.targetPieceCount()
                    + " connections=" + result.connectionCount()
                    + " enemyReservations=" + result.enemyReservations().size());
        } catch (TemplateLoadException | SchematicPlacementException ex) {
            sender.sendMessage("Generation failed: " + ex.getMessage());
            plugin.getLogger().warning("Generation failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("azirouge.command.reload")) {
            sender.sendMessage("You do not have permission to reload AziRouge.");
            return true;
        }
        plugin.reloadPluginState();
        sender.sendMessage("AziRouge configuration reloaded.");
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.debug")) {
            sender.sendMessage("You do not have permission to toggle debug logging.");
            return true;
        }
        boolean enabled;
        if (args.length == 0) {
            enabled = !plugin.debugLogger().isEnabled();
        } else {
            enabled = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "on", "true" -> true;
                case "off", "false" -> false;
                default -> plugin.debugLogger().isEnabled();
            };
        }
        plugin.setDebugEnabled(enabled);
        sender.sendMessage("AziRouge debug logging is now " + (enabled ? "enabled" : "disabled") + ".");
        return true;
    }

    private Map<String, String> parseOptions(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith("--") && arg.contains("="))
                .map(arg -> arg.substring(2).split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0].toLowerCase(Locale.ROOT), parts -> parts[1], (left, right) -> right));
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
