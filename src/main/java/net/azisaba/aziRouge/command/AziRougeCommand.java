package net.azisaba.aziRouge.command;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.author.EntranceAuthoringResult;
import net.azisaba.aziRouge.author.PieceAuthoringResult;
import net.azisaba.aziRouge.author.SelectionLookupException;
import net.azisaba.aziRouge.author.TemplateAuthoringException;
import net.azisaba.aziRouge.config.GenerationSettings;
import net.azisaba.aziRouge.dungeon.DungeonGenerationResult;
import net.azisaba.aziRouge.dungeon.GenerationExecutionRequest;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.schematic.SchematicPlacementException;
import net.azisaba.aziRouge.template.TemplateLoadException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class AziRougeCommand implements CommandExecutor, TabCompleter {
    private final AziRouge plugin;

    public AziRougeCommand(AziRouge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /" + label + " <generate|reload|debug|author>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "generate" -> handleGenerate(sender, Arrays.copyOfRange(args, 1, args.length));
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender, Arrays.copyOfRange(args, 1, args.length));
            case "author" -> handleAuthor(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                sender.sendMessage("Unknown subcommand: " + args[0]);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("generate", "reload", "debug", "author").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length >= 2 && "generate".equalsIgnoreCase(args[0])) {
            List<String> options = List.of("--patterns=", "--start=", "--world=", "--x=", "--y=", "--z=", "--seed=", "--depth=");
            return options.stream().filter(option -> option.startsWith(args[args.length - 1])).toList();
        }
        if (args.length == 2 && "debug".equalsIgnoreCase(args[0])) {
            return List.of("on", "off").stream().filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && "author".equalsIgnoreCase(args[0])) {
            return List.of("piece", "entrance").stream().filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && "author".equalsIgnoreCase(args[0]) && "piece".equalsIgnoreCase(args[1])) {
            return List.of("upsert").stream().filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && "author".equalsIgnoreCase(args[0]) && "entrance".equalsIgnoreCase(args[1])) {
            return List.of("upsert", "remove").stream().filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 8 && "author".equalsIgnoreCase(args[0]) && "entrance".equalsIgnoreCase(args[1]) && "upsert".equalsIgnoreCase(args[2])) {
            return Arrays.stream(Direction.values())
                    .map(Enum::name)
                    .filter(option -> option.startsWith(args[7].toUpperCase(Locale.ROOT)))
                    .toList();
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

        IntVector3 origin;
        if (sender instanceof Player p) {
            origin = new IntVector3(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        } else {
            origin = defaults.origin();
        }
        origin = new IntVector3(
                parseInt(options.get("x"), origin.x()),
                parseInt(options.get("y"), origin.y()),
                parseInt(options.get("z"), origin.z())
        );
        long seed = parseLong(options.get("seed"), System.currentTimeMillis());
        Integer depthOverride = options.containsKey("depth") ? Math.max(1, parseInt(options.get("depth"), defaults.maxDepth())) : null;

        try {
            DungeonGenerationResult result = plugin.dungeonGenerator().generate(
                    new GenerationExecutionRequest(patterns, start, world, origin, seed, depthOverride),
                    plugin.settings()
            );
            sender.sendMessage("Generated dungeon seed=" + result.seed()
                    + " pieces=" + result.placedPieceCount() + "/" + result.targetPieceCount()
                    + " connections=" + result.connectionCount()
                    + " enemyReservations=" + result.enemyReservations().size()
                    + " depth=" + (depthOverride == null ? defaults.maxDepth() : depthOverride));
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

    private boolean handleAuthor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.author")) {
            sender.sendMessage("You do not have permission to edit templates in-game.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Authoring commands can only be run by a player.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /azirouge author <piece|entrance> ...");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "piece" -> handleAuthorPiece(player, Arrays.copyOfRange(args, 1, args.length));
            case "entrance" -> handleAuthorEntrance(player, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                sender.sendMessage("Unknown author target: " + args[0]);
                yield true;
            }
        };
    }

    private boolean handleAuthorPiece(Player player, String[] args) {
        if (args.length < 4 || !"upsert".equalsIgnoreCase(args[0])) {
            player.sendMessage("Usage: /azirouge author piece upsert <templateFile> <pieceId> <schematicPath> [weight]");
            player.sendMessage("Select the whole piece with WorldEdit. The schematic origin will be fixed to the selection's minimum corner.");
            return true;
        }

        String templateFile = args[1];
        String pieceId = args[2];
        String schematicPath = args[3];
        double weight = args.length >= 5 ? parseDouble(args[4], 1.0D) : 1.0D;

        try {
            PieceAuthoringResult result = plugin.templateAuthoringService().upsertPiece(player, templateFile, pieceId, schematicPath, weight);
            player.sendMessage("Piece saved: " + pieceId
                    + " template=" + result.templateFile()
                    + " origin=" + format(result.origin())
                    + " bounds=" + format(result.bounds()));
        } catch (TemplateAuthoringException | SelectionLookupException ex) {
            player.sendMessage("Piece authoring failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleAuthorEntrance(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("Usage: /azirouge author entrance <upsert|remove> ...");
            return true;
        }

        if ("upsert".equalsIgnoreCase(args[0])) {
            if (args.length < 5) {
                player.sendMessage("Usage: /azirouge author entrance upsert <templateFile> <pieceId> <entranceId> <facing>");
                player.sendMessage("Select the entrance plane with WorldEdit. The piece minimum corner saved by `piece upsert` will be reused automatically.");
                return true;
            }
            Direction facing;
            try {
                facing = Direction.valueOf(args[4].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                player.sendMessage("Facing must be one of: NORTH, EAST, SOUTH, WEST");
                return true;
            }

            try {
                EntranceAuthoringResult result = plugin.templateAuthoringService().upsertEntrance(
                        player,
                        args[1],
                        args[2],
                        args[3],
                        facing
                );
                player.sendMessage("Entrance saved: " + args[3]
                        + " facing=" + result.facing()
                        + " origin=" + format(result.origin())
                        + " plane=" + format(result.plane()));
            } catch (TemplateAuthoringException | SelectionLookupException ex) {
                player.sendMessage("Entrance authoring failed: " + ex.getMessage());
            }
            return true;
        }

        if ("remove".equalsIgnoreCase(args[0])) {
            if (args.length < 4) {
                player.sendMessage("Usage: /azirouge author entrance remove <templateFile> <pieceId> <entranceId>");
                return true;
            }
            try {
                plugin.templateAuthoringService().removeEntrance(args[1], args[2], args[3]);
                player.sendMessage("Entrance removed: " + args[3]);
            } catch (TemplateAuthoringException ex) {
                player.sendMessage("Entrance removal failed: " + ex.getMessage());
            }
            return true;
        }

        player.sendMessage("Unknown entrance action: " + args[0]);
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

    private double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String format(IntVector3 vector) {
        return vector.x() + "," + vector.y() + "," + vector.z();
    }

    private String format(net.azisaba.aziRouge.math.BlockBox box) {
        return format(box.min()) + "->" + format(box.max());
    }
}
