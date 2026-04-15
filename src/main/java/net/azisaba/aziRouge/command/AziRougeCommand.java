package net.azisaba.aziRouge.command;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.author.EntranceAuthoringResult;
import net.azisaba.aziRouge.author.PieceAuthoringResult;
import net.azisaba.aziRouge.author.SelectionLookupException;
import net.azisaba.aziRouge.author.TemplateAuthoringException;
import net.azisaba.aziRouge.game.GameSession;
import net.azisaba.aziRouge.config.GenerationSettings;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.dungeon.DungeonGenerationResult;
import net.azisaba.aziRouge.dungeon.GenerationExecutionRequest;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.schematic.SchematicPlacementException;
import net.azisaba.aziRouge.template.TemplateLoadException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class AziRougeCommand implements TabExecutor {
    private static final String PREFIX = ChatColor.GOLD + "[Azirouge] " + ChatColor.RESET;
    // Hardcoded for now; add more presets here as additional template sets are authored.
    private static final Map<String, TemplatePreset> TEMPLATE_PRESETS = Map.of(
            "test", new TemplatePreset(List.of("templates/test.yml"), "root")
    );

    private final AziRouge plugin;

    public AziRougeCommand(AziRouge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            info(sender, "Usage: /" + label + " <start|end|generate|reload|debug|author>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(sender, Arrays.copyOfRange(args, 1, args.length));
            case "end" -> handleEnd(sender);
            case "generate" -> handleGenerate(sender, Arrays.copyOfRange(args, 1, args.length));
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender, Arrays.copyOfRange(args, 1, args.length));
            case "author" -> handleAuthor(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                error(sender, "Unknown subcommand: " + args[0]);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "end", "generate", "reload", "debug", "author").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && "start".equalsIgnoreCase(args[0])) {
            return templatePresetNames().stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length >= 2 && "generate".equalsIgnoreCase(args[0])) {
            if (args[args.length - 1].startsWith("--preset=")) {
                String prefix = args[args.length - 1].substring("--preset=".length()).toLowerCase(Locale.ROOT);
                return templatePresetNames().stream()
                        .map(name -> "--preset=" + name)
                        .filter(option -> option.substring("--preset=".length()).startsWith(prefix))
                        .toList();
            }
            List<String> options = List.of("--preset=", "--patterns=", "--start=", "--world=", "--x=", "--y=", "--z=", "--seed=", "--depth=");
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

    private boolean handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.start")) {
            error(sender, "You do not have permission to start an AziRouge session.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }
        if (args.length > 1) {
            info(sender, "Usage: /azirouge start [template-preset]");
            info(sender, "Available presets: " + String.join(", ", templatePresetNames()));
            return true;
        }
        if (plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).isPresent()) {
            error(sender, "You are already associated with an active session. Use /azirouge end first.");
            return true;
        }

        try {
            TemplateSelection templateSelection = resolveTemplateSelection(
                    plugin.settings().generation(),
                    args.length == 0 ? null : args[0],
                    null,
                    null
            );
            GameSession session = plugin.gameSessionManager().startSession(
                    player,
                    templateSelection.templatePatterns(),
                    templateSelection.startPieceId()
            );
            success(sender, "Started a new session in world " + session.world().getName()
                    + " using template preset " + templateSelection.presetName() + ".");
        } catch (TemplateLoadException | SchematicPlacementException ex) {
            error(sender, "Session start failed: " + ex.getMessage());
            plugin.getLogger().warning("Session start failed: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            error(sender, ex.getMessage());
        } catch (IllegalStateException ex) {
            error(sender, ex.getMessage());
            plugin.getLogger().warning("Session start failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleEnd(CommandSender sender) {
        if (!sender.hasPermission("azirouge.end")) {
            error(sender, "You do not have permission to end an AziRouge session.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            error(sender, "You are not in an active session.");
            return true;
        }

        if (plugin.gameSessionManager().endSession(session)) {
            success(sender, "Ended session world " + session.world().getName() + ".");
        } else {
            error(sender, "Failed to fully end session world " + session.world().getName() + ". Check server logs.");
        }
        return true;
    }

    private boolean handleGenerate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.generate")) {
            error(sender, "You do not have permission to generate dungeons.");
            return true;
        }

        GenerationSettings defaults = plugin.settings().generation();
        Map<String, String> options = parseOptions(args);
        List<String> overriddenPatterns = options.containsKey("patterns")
                ? Arrays.stream(options.get("patterns").split(","))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new))
                : null;
        TemplateSelection templateSelection;
        try {
            templateSelection = resolveTemplateSelection(
                    defaults,
                    options.get("preset"),
                    overriddenPatterns,
                    options.get("start")
            );
        } catch (IllegalArgumentException ex) {
            error(sender, ex.getMessage());
            return true;
        }

        String worldName = options.getOrDefault("world", defaults.worldName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            error(sender, "World not found: " + worldName);
            return true;
        }

        IntVector3 origin;
        if (sender instanceof Player player) {
            origin = new IntVector3(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ()
            );
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
                    new GenerationExecutionRequest(
                            templateSelection.templatePatterns(),
                            templateSelection.startPieceId(),
                            world,
                            origin,
                            seed,
                            depthOverride
                    ),
                    plugin.settings()
            );
            success(sender, "Generated dungeon seed=" + result.seed()
                    + " pieces=" + result.placedPieceCount() + "/" + result.targetPieceCount()
                    + " connections=" + result.connectionCount()
                    + " enemyReservations=" + result.enemyReservations().size()
                    + " depth=" + (depthOverride == null ? defaults.maxDepth() : depthOverride)
                    + " preset=" + templateSelection.presetName());
        } catch (TemplateLoadException | SchematicPlacementException ex) {
            error(sender, "Generation failed: " + ex.getMessage());
            plugin.getLogger().warning("Generation failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("azirouge.command.reload")) {
            error(sender, "You do not have permission to reload AziRouge.");
            return true;
        }
        plugin.reloadPluginState();
        success(sender, "AziRouge configuration reloaded.");
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.debug")) {
            error(sender, "You do not have permission to toggle debug logging.");
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
        success(sender, "AziRouge debug logging is now " + (enabled ? "enabled" : "disabled") + ".");
        return true;
    }

    private boolean handleAuthor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.author")) {
            error(sender, "You do not have permission to edit templates in-game.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Authoring commands can only be run by a player.");
            return true;
        }
        if (args.length == 0) {
            info(sender, "Usage: /azirouge author <piece|entrance> ...");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "piece" -> handleAuthorPiece(player, Arrays.copyOfRange(args, 1, args.length));
            case "entrance" -> handleAuthorEntrance(player, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                error(sender, "Unknown author target: " + args[0]);
                yield true;
            }
        };
    }

    private boolean handleAuthorPiece(Player player, String[] args) {
        if (args.length < 4 || !"upsert".equalsIgnoreCase(args[0])) {
            info(player, "Usage: /azirouge author piece upsert <templateFile> <pieceId> <schematicPath> [weight]");
            info(player, "Select the whole piece with WorldEdit. The schematic origin will be fixed to the selection's minimum corner.");
            return true;
        }

        String templateFile = args[1];
        String pieceId = args[2];
        String schematicPath = args[3];
        double weight = args.length >= 5 ? parseDouble(args[4], 1.0D) : 1.0D;

        try {
            PieceAuthoringResult result = plugin.templateAuthoringService().upsertPiece(player, templateFile, pieceId, schematicPath, weight);
            success(player, "Piece saved: " + pieceId
                    + " template=" + result.templateFile()
                    + " origin=" + format(result.origin())
                    + " bounds=" + format(result.bounds()));
        } catch (TemplateAuthoringException | SelectionLookupException ex) {
            error(player, "Piece authoring failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleAuthorEntrance(Player player, String[] args) {
        if (args.length == 0) {
            info(player, "Usage: /azirouge author entrance <upsert|remove> ...");
            return true;
        }

        if ("upsert".equalsIgnoreCase(args[0])) {
            if (args.length < 5) {
                info(player, "Usage: /azirouge author entrance upsert <templateFile> <pieceId> <entranceId> <facing>");
                info(player, "Select the entrance plane with WorldEdit. The piece minimum corner saved by `piece upsert` will be reused automatically.");
                return true;
            }
            Direction facing;
            try {
                facing = Direction.valueOf(args[4].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                error(player, "Facing must be one of: NORTH, EAST, SOUTH, WEST");
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
                success(player, "Entrance saved: " + args[3]
                        + " facing=" + result.facing()
                        + " origin=" + format(result.origin())
                        + " plane=" + format(result.plane()));
            } catch (TemplateAuthoringException | SelectionLookupException ex) {
                error(player, "Entrance authoring failed: " + ex.getMessage());
            }
            return true;
        }

        if ("remove".equalsIgnoreCase(args[0])) {
            if (args.length < 4) {
                info(player, "Usage: /azirouge author entrance remove <templateFile> <pieceId> <entranceId>");
                return true;
            }
            try {
                plugin.templateAuthoringService().removeEntrance(args[1], args[2], args[3]);
                success(player, "Entrance removed: " + args[3]);
            } catch (TemplateAuthoringException ex) {
                error(player, "Entrance removal failed: " + ex.getMessage());
            }
            return true;
        }

        error(player, "Unknown entrance action: " + args[0]);
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

    private void info(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.YELLOW + message);
    }

    private void success(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.GREEN + message);
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.RED + message);
    }

    private TemplateSelection resolveTemplateSelection(
            GenerationSettings defaults,
            String presetName,
            List<String> overriddenPatterns,
            String overriddenStart
    ) {
        String normalizedPreset = presetName == null || presetName.isBlank()
                ? "default"
                : presetName.toLowerCase(Locale.ROOT);
        List<String> patterns = defaults.templatePatterns();
        String startPieceId = defaults.startPieceId();

        if (!"default".equals(normalizedPreset)) {
            TemplatePreset preset = TEMPLATE_PRESETS.get(normalizedPreset);
            if (preset == null) {
                throw new IllegalArgumentException("Unknown template preset: " + presetName
                        + ". Available presets: " + String.join(", ", templatePresetNames()));
            }
            patterns = preset.templatePatterns();
            startPieceId = preset.startPieceId();
        }

        if (overriddenPatterns != null && !overriddenPatterns.isEmpty()) {
            patterns = List.copyOf(overriddenPatterns);
        }
        if (overriddenStart != null && !overriddenStart.isBlank()) {
            startPieceId = overriddenStart;
        }

        return new TemplateSelection(List.copyOf(patterns), startPieceId, normalizedPreset);
    }

    private List<String> templatePresetNames() {
        List<String> names = new ArrayList<>();
        names.add("default");
        names.addAll(TEMPLATE_PRESETS.keySet().stream().sorted().toList());
        return names;
    }

    private String format(IntVector3 vector) {
        return vector.x() + "," + vector.y() + "," + vector.z();
    }

    private String format(BlockBox box) {
        return format(box.min()) + "->" + format(box.max());
    }

    private record TemplatePreset(List<String> templatePatterns, String startPieceId) {
        private TemplatePreset {
            Objects.requireNonNull(templatePatterns, "templatePatterns");
            Objects.requireNonNull(startPieceId, "startPieceId");
        }
    }

    private record TemplateSelection(List<String> templatePatterns, String startPieceId, String presetName) {
    }
}
