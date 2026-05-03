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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
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
            info(sender, "Usage: /" + label + " <start|end|session|round|dungeon|money|generate|reload|debug|author>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(sender, Arrays.copyOfRange(args, 1, args.length));
            case "end" -> handleEnd(sender);
            case "session" -> handleSession(sender, Arrays.copyOfRange(args, 1, args.length));
            case "round" -> handleRound(sender, Arrays.copyOfRange(args, 1, args.length));
            case "dungeon" -> handleDungeon(sender, Arrays.copyOfRange(args, 1, args.length));
            case "money" -> handleMoney(sender, Arrays.copyOfRange(args, 1, args.length));
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
            return List.of("start", "end", "session", "round", "dungeon", "money", "generate", "reload", "debug", "author").stream()
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
        if (args.length == 2 && "session".equalsIgnoreCase(args[0])) {
            return List.of("create", "join", "leave", "list", "forceend").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && "round".equalsIgnoreCase(args[0])) {
            return List.of("start", "end").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && "round".equalsIgnoreCase(args[0]) && "start".equalsIgnoreCase(args[1])) {
            return templatePresetNames().stream()
                    .filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && "dungeon".equalsIgnoreCase(args[0])) {
            return List.of("select").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && "money".equalsIgnoreCase(args[0])) {
            return List.of("set", "add").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && "money".equalsIgnoreCase(args[0])
                && ("set".equalsIgnoreCase(args[1]) || "add".equalsIgnoreCase(args[1]))) {
            return plugin.gameSessionManager().sessions().stream()
                    .map(GameSession::sessionId)
                    .filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && "dungeon".equalsIgnoreCase(args[0]) && "select".equalsIgnoreCase(args[1])) {
            return templatePresetNames().stream()
                    .filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && "session".equalsIgnoreCase(args[0])
                && ("join".equalsIgnoreCase(args[1]) || "forceend".equalsIgnoreCase(args[1]))) {
            return plugin.gameSessionManager().sessions().stream()
                    .map(GameSession::sessionId)
                    .filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
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

        TemplateSelection templateSelection;
        try {
            templateSelection = resolveTemplateSelection(
                    plugin.settings().generation(),
                    args.length == 0 ? null : args[0],
                    null,
                    null
            );
        } catch (IllegalArgumentException ex) {
            error(sender, ex.getMessage());
            return true;
        }
        info(sender, "Creating session...");
        plugin.gameSessionManager().startSessionAsync(
                player,
                templateSelection.templatePatterns(),
                templateSelection.startPieceId()
        ).whenComplete((session, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                reportSessionCreationFailure(sender, "Session start failed", ex);
                return;
            }
            success(sender, "Started a new session in world " + session.world().getName()
                    + " id=" + session.sessionId()
                    + " money=" + session.sharedBalance()
                    + " using template preset " + templateSelection.presetName() + ".");
            sendCopyableSessionId(sender, session);
        }));
        return true;
    }

    private boolean handleSession(CommandSender sender, String[] args) {
        if (args.length == 0) {
            info(sender, "Usage: /azirouge session <create|join|leave|list|forceend>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> handleSessionCreate(sender, Arrays.copyOfRange(args, 1, args.length));
            case "join" -> handleSessionJoin(sender, Arrays.copyOfRange(args, 1, args.length));
            case "leave" -> handleSessionLeave(sender);
            case "list" -> handleSessionList(sender);
            case "forceend" -> handleSessionForceEnd(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                error(sender, "Unknown session subcommand: " + args[0]);
                yield true;
            }
        };
    }

    private boolean handleSessionCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.session")) {
            error(sender, "You do not have permission to manage AziRouge sessions.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }
        if (args.length > 1) {
            info(sender, "Usage: /azirouge session create [maxPlayers]");
            return true;
        }

        int maxPlayers = args.length == 0
                ? plugin.settings().sessions().defaultMaxPlayers()
                : parseInt(args[0], plugin.settings().sessions().defaultMaxPlayers());
        info(sender, "Creating session...");
        plugin.gameSessionManager().startSessionAsync(player,
                plugin.settings().generation().templatePatterns(),
                plugin.settings().generation().startPieceId(),
                maxPlayers
        ).whenComplete((session, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                reportSessionCreationFailure(sender, "Session creation failed", ex);
                return;
            }
            success(sender, "Created session " + session.sessionId()
                    + " players=1/" + session.maxPlayers()
                    + " money=" + session.sharedBalance()
                    + " world=" + session.world().getName() + ".");
            sendCopyableSessionId(sender, session);
        }));
        return true;
    }

    private boolean handleSessionJoin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.session")) {
            error(sender, "You do not have permission to manage AziRouge sessions.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }
        if (args.length != 1) {
            info(sender, "Usage: /azirouge session join <sessionId>");
            return true;
        }

        try {
            GameSession session = plugin.gameSessionManager().joinSession(player, args[0]);
            success(sender, "Joined session " + session.sessionId()
                    + " players=" + session.members().size() + "/" + session.maxPlayers() + ".");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            error(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleSessionLeave(CommandSender sender) {
        if (!sender.hasPermission("azirouge.session")) {
            error(sender, "You do not have permission to manage AziRouge sessions.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }

        try {
            GameSession session = plugin.gameSessionManager().leaveSession(player);
            success(sender, "Left session " + session.sessionId() + ".");
        } catch (IllegalStateException ex) {
            error(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleSessionList(CommandSender sender) {
        if (!sender.hasPermission("azirouge.session.list")) {
            error(sender, "You do not have permission to manage AziRouge sessions.");
            return true;
        }
        List<GameSession> sessions = plugin.gameSessionManager().sessions().stream()
                .sorted((left, right) -> left.sessionId().compareTo(right.sessionId()))
                .toList();
        if (sessions.isEmpty()) {
            info(sender, "No active sessions.");
            return true;
        }

        info(sender, "Active sessions:");
        for (GameSession session : sessions) {
            info(sender, session.sessionId()
                    + " state=" + session.state()
                    + " roundState=" + session.roundState()
                    + " money=" + session.sharedBalance()
                    + " players=" + session.onlineMembers().size() + "/" + session.members().size() + "/" + session.maxPlayers()
                    + " round=" + session.currentRound()
                    + " alive=" + session.alivePlayers().size()
                    + " dead=" + session.deadPlayers().size()
                    + " pending=" + session.pendingPlayersNextRound().size()
                    + " preset=" + session.selectedPreset()
                    + " maxDepth=" + session.getMaxDepth()
                    + " world=" + session.world().getName()
                    + " owner=" + session.owner());
        }
        return true;
    }

    private boolean handleSessionForceEnd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.session.forceend") && !sender.hasPermission("azirouge.end")) {
            error(sender, "You do not have permission to force-end AziRouge sessions.");
            return true;
        }
        if (args.length != 1) {
            info(sender, "Usage: /azirouge session forceend <sessionId>");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionById(args[0]).orElse(null);
        if (session == null) {
            error(sender, "Session not found: " + args[0]);
            return true;
        }
        if (plugin.gameSessionManager().endSession(session)) {
            success(sender, "Force-ended session " + session.sessionId() + ".");
        } else {
            error(sender, "Failed to fully end session " + session.sessionId() + ". Check server logs.");
        }
        return true;
    }

    private boolean handleRound(CommandSender sender, String[] args) {
        if (args.length == 0) {
            info(sender, "Usage: /azirouge round <start|end>");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleRoundStart(sender, Arrays.copyOfRange(args, 1, args.length));
            case "end" -> handleRoundEnd(sender);
            default -> {
                error(sender, "Unknown round subcommand: " + args[0]);
                yield true;
            }
        };
    }

    private boolean handleRoundStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.session")) {
            error(sender, "You do not have permission to manage AziRouge rounds.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }
        if (args.length > 2) {
            info(sender, "Usage: /azirouge round start [preset] [maxDepth]");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            error(sender, "You are not in an active session.");
            return true;
        }

        Integer maxDepth = parsePositiveInt(args.length >= 2 ? args[1] : null, session.getMaxDepth());
        if (maxDepth == null) {
            error(sender, "maxDepth must be a positive integer.");
            return true;
        }
        String presetName = args.length >= 1 && !args[0].isBlank()
                ? args[0].toLowerCase(Locale.ROOT)
                : session.selectedPreset();

        try {
            TemplateSelection templateSelection = resolveTemplateSelection(
                    plugin.settings().generation(),
                    presetName,
                    null,
                    null
            );
            DungeonGenerationResult result = plugin.gameSessionManager().startRound(
                    session,
                    templateSelection.templatePatterns(),
                    templateSelection.startPieceId(),
                    templateSelection.presetName(),
                    maxDepth
            );
            success(sender, "Started round " + session.currentRound()
                    + " session=" + session.sessionId()
                    + " preset=" + templateSelection.presetName()
                    + " maxDepth=" + maxDepth
                    + " maintenanceDueAtEnd=" + plugin.economyService().maintenanceCostForRound(session.currentRound())
                    + " balance=" + session.sharedBalance()
                    + " pieces=" + result.placedPieceCount() + "/" + result.targetPieceCount()
                    + " origin=" + format(session.currentDungeonOrigin()));
        } catch (TemplateLoadException | SchematicPlacementException ex) {
            error(sender, "Round start failed: " + ex.getMessage());
            plugin.getLogger().warning("Round start failed: " + ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            error(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleRoundEnd(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }
        try {
            var sellResult = plugin.gameSessionManager().endRound(player);
            GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
            success(sender, "Ended round" + (session == null ? "." : " " + session.currentRound()
                    + ". soldItems=" + sellResult.itemCount()
                    + " sold=" + sellResult.totalAmount()
                    + " maintenance=" + sellResult.maintenanceCost()
                    + " maintenancePaid=" + sellResult.maintenancePaid()
                    + " balance=" + session.sharedBalance() + "."));
        } catch (IllegalStateException ex) {
            error(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleMoney(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.money")) {
            error(sender, "You do not have permission to manage AziRouge money.");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                info(sender, "Usage: /azirouge money <set|add> <sessionId> <amount>");
                return true;
            }
            GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
            if (session == null) {
                error(sender, "You are not in an active session.");
                return true;
            }
            int maintenanceRound = session.state() == net.azisaba.aziRouge.game.SessionState.IN_ROUND
                    ? session.currentRound()
                    : session.currentRound() + 1;
            long nextMaintenance = plugin.economyService().maintenanceCostForRound(maintenanceRound);
            info(sender, "Session " + session.sessionId()
                    + " money=" + session.sharedBalance()
                    + " maintenanceRound=" + maintenanceRound
                    + " maintenance=" + nextMaintenance + ".");
            return true;
        }

        if (args.length != 3 || (!"set".equalsIgnoreCase(args[0]) && !"add".equalsIgnoreCase(args[0]))) {
            info(sender, "Usage: /azirouge money [set|add] <sessionId> <amount>");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionById(args[1]).orElse(null);
        if (session == null) {
            error(sender, "Session not found: " + args[1]);
            return true;
        }
        Long amount = parseNonNegativeLong(args[2]);
        if (amount == null) {
            error(sender, "Amount must be a non-negative integer.");
            return true;
        }

        if ("set".equalsIgnoreCase(args[0])) {
            session.setSharedBalance(amount);
            success(sender, "Set session " + session.sessionId() + " money to " + session.sharedBalance() + ".");
        } else {
            session.addSharedBalance(amount);
            success(sender, "Added " + amount + " to session " + session.sessionId()
                    + ". money=" + session.sharedBalance() + ".");
        }
        return true;
    }

    private boolean handleDungeon(CommandSender sender, String[] args) {
        if (args.length == 0) {
            info(sender, "Usage: /azirouge dungeon select <preset> [maxDepth]");
            return true;
        }
        if (!"select".equalsIgnoreCase(args[0])) {
            error(sender, "Unknown dungeon subcommand: " + args[0]);
            return true;
        }
        return handleDungeonSelect(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private boolean handleDungeonSelect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.session")) {
            error(sender, "You do not have permission to select AziRouge dungeons.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "This command can only be run by a player.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            info(sender, "Usage: /azirouge dungeon select <preset> [maxDepth]");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            error(sender, "You are not in an active session.");
            return true;
        }
        Integer maxDepth = parsePositiveInt(args.length >= 2 ? args[1] : null, session.getMaxDepth());
        if (maxDepth == null) {
            error(sender, "maxDepth must be a positive integer.");
            return true;
        }
        try {
            TemplateSelection templateSelection = resolveTemplateSelection(
                    plugin.settings().generation(),
                    args[0],
                    null,
                    null
            );
            plugin.gameSessionManager().selectDungeon(session, templateSelection.presetName(), maxDepth);
            success(sender, "Selected dungeon preset=" + templateSelection.presetName()
                    + " maxDepth=" + maxDepth
                    + " for session " + session.sessionId() + ".");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            error(sender, ex.getMessage());
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

    private Integer parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return Math.max(1, fallback);
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed <= 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            try {
                int parsed = Math.round(Float.parseFloat(value));
                return parsed <= 0 ? null : parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
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

    private Long parseNonNegativeLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0L ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
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

    private void sendCopyableSessionId(CommandSender sender, GameSession session) {
        sender.sendMessage(Component.text("[Azirouge] ", NamedTextColor.GOLD)
                .append(Component.text("Session ID: ", NamedTextColor.YELLOW))
                .append(Component.text(session.sessionId(), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(session.sessionId())))
                .append(Component.text(" (click to copy)", NamedTextColor.GRAY)));
    }

    private void reportSessionCreationFailure(CommandSender sender, String prefix, Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        error(sender, prefix + ": " + message);
        plugin.getLogger().warning(prefix + ": " + message);
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
