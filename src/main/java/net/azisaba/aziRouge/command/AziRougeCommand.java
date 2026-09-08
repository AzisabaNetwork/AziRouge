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
import net.azisaba.aziRouge.statistics.PlayerStatistics;
import org.bukkit.Bukkit;
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
    private static final String CONFIRM_FLAG = "--confirm";
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
            if (sender instanceof Player player) {
                plugin.gameMenuService().openMenu(player);
                return true;
            }
            tell(sender, "commands.usage.root", "&e使い方: /{label} <start|end|session|round|dungeon|money|stats|generate|reload|debug|author>", "label", label);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleStart(sender, Arrays.copyOfRange(args, 1, args.length));
            case "end" -> handleEnd(sender, Arrays.copyOfRange(args, 1, args.length));
            case "menu" -> handleMenu(sender);
            case "session" -> handleSession(sender, Arrays.copyOfRange(args, 1, args.length));
            case "round" -> handleRound(sender, Arrays.copyOfRange(args, 1, args.length));
            case "dungeon" -> handleDungeon(sender, Arrays.copyOfRange(args, 1, args.length));
            case "money" -> handleMoney(sender, Arrays.copyOfRange(args, 1, args.length));
            case "stats" -> handleStats(sender, Arrays.copyOfRange(args, 1, args.length));
            case "generate" -> handleGenerate(sender, Arrays.copyOfRange(args, 1, args.length));
            case "reload" -> handleReload(sender, Arrays.copyOfRange(args, 1, args.length));
            case "debug" -> handleDebug(sender, Arrays.copyOfRange(args, 1, args.length));
            case "author" -> handleAuthor(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                tell(sender, "commands.unknown.subcommand", "&c不明なサブコマンドです: {subcommand}", "subcommand", args[0]);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "end", "session", "round", "dungeon", "money", "stats", "generate", "reload", "debug", "author").stream()
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
        if (args.length == 2 && "stats".equalsIgnoreCase(args[0]) && sender.hasPermission("azirouge.stats.others")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
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

    private boolean handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        plugin.gameMenuService().openMenu(player);
        return true;
    }

    private boolean handleStart(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.start")) {
            tell(sender, "commands.permission.start", "&cAziRougeセッションを開始する権限がありません。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length > 1) {
            tell(sender, "commands.usage.start", "&e使い方: /azirouge start [template-preset]");
            tell(sender, "commands.presets", "&e利用可能なプリセット: {presets}", "presets", String.join(", ", templatePresetNames()));
            return true;
        }
        if (plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).isPresent()) {
            tell(sender, "commands.already-in-session", "&cすでにセッションに参加しています。先に /azirouge end を実行してください。");
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
            tellRaw(sender, ex.getMessage());
            return true;
        }
        tell(sender, "session.creating", "&eセッションを作成しています...");
        plugin.gameSessionManager().startSessionAsync(
                player,
                templateSelection.templatePatterns(),
                templateSelection.startPieceId()
        ).whenComplete((session, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                reportSessionCreationFailure(sender, "commands.start-failed", "&cセッション開始に失敗しました: {reason}", ex);
                return;
            }
            tell(sender, "session.started-detail", "&aセッションを開始しました。ワールド {world} / ID {session} / 資金 {money} / プリセット {preset}",
                    "world", session.world().getName(),
                    "session", session.sessionId(),
                    "money", session.sharedBalance(),
                    "preset", templateSelection.presetName());
            sendCopyableSessionId(sender, session);
        }));
        return true;
    }

    private boolean handleSession(CommandSender sender, String[] args) {
        if (args.length == 0) {
            tell(sender, "commands.usage.session", "&e使い方: /azirouge session <create|join|leave|list|forceend>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> handleSessionCreate(sender, Arrays.copyOfRange(args, 1, args.length));
            case "join" -> handleSessionJoin(sender, Arrays.copyOfRange(args, 1, args.length));
            case "leave" -> handleSessionLeave(sender, Arrays.copyOfRange(args, 1, args.length));
            case "list" -> handleSessionList(sender);
            case "forceend" -> handleSessionForceEnd(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                tell(sender, "commands.unknown.session", "&c不明なセッションサブコマンドです: {subcommand}", "subcommand", args[0]);
                yield true;
            }
        };
    }

    private boolean handleSessionCreate(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.session")) {
            tell(sender, "commands.permission.session", "&cAziRougeセッションを操作する権限がありません。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length > 1) {
            tell(sender, "commands.usage.session-create", "&e使い方: /azirouge session create [maxPlayers]");
            return true;
        }

        int maxPlayers = args.length == 0
                ? plugin.settings().sessions().defaultMaxPlayers()
                : parseInt(args[0], plugin.settings().sessions().defaultMaxPlayers());
        tell(sender, "session.creating", "&eセッションを作成しています...");
        plugin.gameSessionManager().startSessionAsync(player,
                plugin.settings().generation().templatePatterns(),
                plugin.settings().generation().startPieceId(),
                maxPlayers
        ).whenComplete((session, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                reportSessionCreationFailure(sender, "commands.create-failed", "&cセッション作成に失敗しました: {reason}", ex);
                return;
            }
            tell(sender, "session.created-detail", "&aセッション {session} を作成しました。人数 1/{max} / 資金 {money} / ワールド {world}",
                    "session", session.sessionId(),
                    "max", session.maxPlayers(),
                    "money", session.sharedBalance(),
                    "world", session.world().getName());
            sendCopyableSessionId(sender, session);
        }));
        return true;
    }

    private boolean handleSessionJoin(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.session")) {
            tell(sender, "commands.permission.session", "&cAziRougeセッションを操作する権限がありません。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length != 1) {
            tell(sender, "commands.usage.session-join", "&e使い方: /azirouge session join <sessionId>");
            return true;
        }
        try {
            GameSession session = plugin.gameSessionManager().joinSession(player, args[0]);
            tell(sender, "session.joined-detail", "&aセッション {session} に参加しました。人数 {players}/{max}",
                    "session", session.sessionId(),
                    "players", session.members().size(),
                    "max", session.maxPlayers());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            tellRaw(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleSessionLeave(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.session")) {
            tell(sender, "commands.permission.session", "&cAziRougeセッションを操作する権限がありません。");
            return true;
        }
        if (args.length != 0) {
            tell(sender, "commands.usage.session-leave", "&e使い方: /azirouge session leave");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        return handleSessionLeave(sender, player);
    }

    private boolean handleSessionLeave(CommandSender sender, Player player) {
        try {
            GameSession session = plugin.gameSessionManager().leaveSession(player);
            tell(sender, "session.left", "&eセッション {session} から退出しました。", "session", session.sessionId());
        } catch (IllegalStateException ex) {
            tellRaw(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleSessionList(CommandSender sender) {
        if (!sender.hasPermission("azirouge.session.list")) {
            tell(sender, "commands.permission.session-list", "&cAziRougeセッション一覧を見る権限がありません。");
            return true;
        }
        List<GameSession> sessions = plugin.gameSessionManager().sessions().stream()
                .sorted((left, right) -> left.sessionId().compareTo(right.sessionId()))
                .toList();
        if (sessions.isEmpty()) {
            tell(sender, "commands.no-sessions", "&e稼働中のセッションはありません。");
            return true;
        }

        tell(sender, "commands.active-sessions", "&e稼働中のセッション:");
        for (GameSession session : sessions) {
            tell(sender, "commands.session-line",
                    "&e{session} 状態={state} ラウンド状態={roundState} 資金={money} 人数={online}/{members}/{max} ラウンド={round} 生存={alive} 脱落={dead} 待機={pending} プリセット={preset} 深さ={maxDepth} ワールド={world} 作成者={owner}",
                    "session", session.sessionId(),
                    "state", plugin.messages().text("scoreboard.states." + session.state().displayKey(), session.state().name()),
                    "roundState", session.roundState(),
                    "money", session.sharedBalance(),
                    "online", session.onlineMembers().size(),
                    "members", session.members().size(),
                    "max", session.maxPlayers(),
                    "round", session.currentRound(),
                    "alive", session.alivePlayers().size(),
                    "dead", session.deadPlayers().size(),
                    "pending", session.pendingPlayersNextRound().size(),
                    "preset", session.selectedPreset(),
                    "maxDepth", session.getMaxDepth(),
                    "world", session.world().getName(),
                    "owner", session.owner());
        }
        return true;
    }

    private boolean handleSessionForceEnd(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.session.forceend") && !sender.hasPermission("azirouge.end")) {
            tell(sender, "commands.permission.session-forceend", "&cAziRougeセッションを強制終了する権限がありません。");
            return true;
        }
        if (args.length != 1) {
            tell(sender, "commands.usage.session-forceend", "&e使い方: /azirouge session forceend <sessionId>");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionById(args[0]).orElse(null);
        if (session == null) {
            tell(sender, "session.error.not-found", "&cセッションが見つかりません: {session}", "session", args[0]);
            return true;
        }
        if (plugin.gameSessionManager().endSession(session)) {
            tell(sender, "commands.force-ended", "&aセッション {session} を強制終了しました。", "session", session.sessionId());
        } else {
            tell(sender, "commands.force-end-failed", "&cセッション {session} を完全に終了できませんでした。サーバーログを確認してください。", "session", session.sessionId());
        }
        return true;
    }

    private boolean handleRound(CommandSender sender, String[] args) {
        if (args.length == 0) {
            tell(sender, "commands.usage.round", "&e使い方: /azirouge round <start|end>");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> handleRoundStart(sender, Arrays.copyOfRange(args, 1, args.length));
            case "end" -> handleRoundEnd(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                tell(sender, "commands.unknown.round", "&c不明なラウンドサブコマンドです: {subcommand}", "subcommand", args[0]);
                yield true;
            }
        };
    }

    private boolean handleRoundStart(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.session")) {
            tell(sender, "commands.permission.round", "&cAziRougeラウンドを操作する権限がありません。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length > 2) {
            tell(sender, "commands.usage.round-start", "&e使い方: /azirouge round start [preset] [maxDepth]");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            tell(sender, "session.error.not-in-session", "&cセッションに参加していません。");
            return true;
        }

        Integer maxDepth = parsePositiveInt(args.length >= 2 ? args[1] : null, session.getMaxDepth());
        if (maxDepth == null) {
            tell(sender, "round.error.max-depth", "&c深さは1以上の整数にしてください。");
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
            tell(sender, "round.started-detail",
                    "&aラウンド {round} を開始しました。セッション {session} / プリセット {preset} / 深さ {depth} / 終了時維持費 {maintenance} / 残高 {balance} / ピース {pieces}/{target} / 原点 {origin}",
                    "round", session.currentRound(),
                    "session", session.sessionId(),
                    "preset", templateSelection.presetName(),
                    "depth", maxDepth,
                    "maintenance", plugin.economyService().maintenanceCostForRound(session.currentRound()),
                    "balance", session.sharedBalance(),
                    "pieces", result.placedPieceCount(),
                    "target", result.targetPieceCount(),
                    "origin", format(session.currentDungeonOrigin()));
        } catch (TemplateLoadException | SchematicPlacementException ex) {
            tell(sender, "round.error.start-failed", "&cラウンド開始に失敗しました: {reason}", "reason", ex.getMessage());
            plugin.getLogger().warning("Round start failed: " + ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            tellRaw(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleRoundEnd(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length != 0) {
            tell(sender, "commands.usage.round-end", "&e使い方: /azirouge round end");
            return true;
        }
        try {
            var sellResult = plugin.gameSessionManager().endRound(player);
            GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
            if (session == null) {
                tell(sender, "round.ended-summary", "&aラウンド {round} 終了。売却数: {items} / 獲得: {amount}",
                        "round", "",
                        "items", sellResult.itemCount(),
                        "amount", sellResult.totalAmount());
            } else {
                tell(sender, "round.ended-detail",
                        "&aラウンド {round} を終了しました。売却 {items}個 / 獲得 {amount} / 維持費 {maintenance} / 支払い {paid} / 残高 {balance}",
                        "round", session.currentRound(),
                        "items", sellResult.itemCount(),
                        "amount", sellResult.totalAmount(),
                        "maintenance", sellResult.maintenanceCost(),
                        "paid", sellResult.maintenancePaid(),
                        "balance", session.sharedBalance());
            }
        } catch (IllegalStateException ex) {
            tellRaw(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleMoney(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.money")) {
            tell(sender, "commands.permission.money", "&cAziRougeの資金を操作する権限がありません。");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                tell(sender, "commands.usage.money-console", "&e使い方: /azirouge money <set|add> <sessionId> <amount>");
                return true;
            }
            GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
            if (session == null) {
                tell(sender, "session.error.not-in-session", "&cセッションに参加していません。");
                return true;
            }
            int maintenanceRound = session.state() == net.azisaba.aziRouge.game.SessionState.IN_ROUND
                    ? session.currentRound()
                    : session.currentRound() + 1;
            long nextMaintenance = plugin.economyService().maintenanceCostForRound(maintenanceRound);
            tell(sender, "commands.money-status", "&eセッション {session} 資金={money} 維持費ラウンド={round} 維持費={maintenance}",
                    "session", session.sessionId(),
                    "money", session.sharedBalance(),
                    "round", maintenanceRound,
                    "maintenance", nextMaintenance);
            return true;
        }

        if (args.length != 3 || (!"set".equalsIgnoreCase(args[0]) && !"add".equalsIgnoreCase(args[0]))) {
            tell(sender, "commands.usage.money", "&e使い方: /azirouge money [set|add] <sessionId> <amount>");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionById(args[1]).orElse(null);
        if (session == null) {
            tell(sender, "session.error.not-found", "&cセッションが見つかりません: {session}", "session", args[1]);
            return true;
        }
        Long amount = parseNonNegativeLong(args[2]);
        if (amount == null) {
            tell(sender, "commands.amount-invalid", "&c金額は0以上の整数にしてください。");
            return true;
        }

        if ("set".equalsIgnoreCase(args[0])) {
            session.setSharedBalance(amount);
            tell(sender, "commands.money-set", "&aセッション {session} の資金を {money} に設定しました。",
                    "session", session.sessionId(),
                    "money", session.sharedBalance());
        } else {
            session.addSharedBalance(amount);
            tell(sender, "commands.money-add", "&aセッション {session} に {amount} を加算しました。資金={money}",
                    "session", session.sessionId(),
                    "amount", amount,
                    "money", session.sharedBalance());
        }
        return true;
    }

    private boolean handleDungeon(CommandSender sender, String[] args) {
        if (args.length == 0) {
            tell(sender, "commands.usage.dungeon", "&e使い方: /azirouge dungeon select <preset> [maxDepth]");
            return true;
        }
        if (!"select".equalsIgnoreCase(args[0])) {
            tell(sender, "commands.unknown.dungeon", "&c不明なダンジョンサブコマンドです: {subcommand}", "subcommand", args[0]);
            return true;
        }
        return handleDungeonSelect(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.stats")) {
            tell(sender, "commands.permission.stats", "&cAziRougeの統計を見る権限がありません。");
            return true;
        }
        if (args.length > 1) {
            tell(sender, "commands.usage.stats", "&e使い方: /azirouge stats [onlinePlayer]");
            return true;
        }
        String targetName;
        java.util.concurrent.CompletableFuture<java.util.Optional<PlayerStatistics>> statisticsFuture;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                tell(sender, "commands.usage.stats-console", "&e使い方: /azirouge stats <player>");
                return true;
            }
            targetName = player.getName();
            statisticsFuture = plugin.statisticsService().playerStatistics(player.getUniqueId());
        } else {
            if (!sender.hasPermission("azirouge.stats.others")) {
                tell(sender, "commands.permission.stats-others", "&c他プレイヤーの統計を見る権限がありません。");
                return true;
            }
            Player onlineTarget = Bukkit.getPlayerExact(args[0]);
            targetName = onlineTarget == null ? args[0] : onlineTarget.getName();
            statisticsFuture = onlineTarget == null
                    ? plugin.statisticsService().playerStatistics(targetName)
                    : plugin.statisticsService().playerStatistics(onlineTarget.getUniqueId());
        }

        tell(sender, "commands.stats-loading", "&e{player} の統計を読み込んでいます...", "player", targetName);
        statisticsFuture.whenComplete((statistics, throwable) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (throwable != null || !plugin.statisticsService().isAvailable()) {
                    tell(sender, "commands.stats-unavailable", "&c統計データベースを利用できません。");
                    return;
                }
                if (statistics.isEmpty()) {
                    tell(sender, "commands.stats-none", "&e{player} のAziRouge統計はまだありません。", "player", targetName);
                    return;
                }
                sendStatistics(sender, statistics.get());
            });
        });
        return true;
    }

    private void sendStatistics(CommandSender sender, PlayerStatistics statistics) {
        tell(sender, "commands.stats-header", "&e統計: {player}", "player", statistics.playerName());
        tell(sender, "commands.stats-rounds", "&e最高ラウンド={maxRound} / 到達合計={totalRounds} / セッション={sessions}",
                "maxRound", statistics.maxRound(),
                "totalRounds", statistics.totalRoundsReached(),
                "sessions", statistics.sessionsJoined());
        tell(sender, "commands.stats-deaths", "&e死亡={deaths} / ゲームオーバー={gameOvers} / 最大深さ={maxDepth}",
                "deaths", statistics.deaths(),
                "gameOvers", statistics.gameOvers(),
                "maxDepth", statistics.maxDepth());
        tell(sender, "commands.stats-playtime", "&eプレイ時間={playTime} / 最長={longest}",
                "playTime", formatDuration(statistics.totalPlaySeconds()),
                "longest", formatDuration(statistics.longestPlaySeconds()));
        tell(sender, "commands.stats-kills", "&eMob撃破={kills} / 宝箱={chests} / 宝={treasures}",
                "kills", statistics.mobKills(),
                "chests", statistics.chestsOpened(),
                "treasures", statistics.treasuresCollected());
        tell(sender, "commands.stats-sales", "&e売却合計={sales} / 途中退出={earlyLeaves} / 切断={disconnects}",
                "sales", statistics.totalSales(),
                "earlyLeaves", statistics.earlyLeaves(),
                "disconnects", statistics.disconnects());
    }

    private String formatDuration(long totalSeconds) {
        long normalized = Math.max(0L, totalSeconds);
        long hours = normalized / 3600L;
        long minutes = (normalized % 3600L) / 60L;
        long seconds = normalized % 60L;
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
    }

    private boolean handleDungeonSelect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.session")) {
            tell(sender, "commands.permission.dungeon", "&cAziRougeのダンジョンを選ぶ権限がありません。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            tell(sender, "commands.usage.dungeon", "&e使い方: /azirouge dungeon select <preset> [maxDepth]");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            tell(sender, "session.error.not-in-session", "&cセッションに参加していません。");
            return true;
        }
        Integer maxDepth = parsePositiveInt(args.length >= 2 ? args[1] : null, session.getMaxDepth());
        if (maxDepth == null) {
            tell(sender, "round.error.max-depth", "&c深さは1以上の整数にしてください。");
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
            tell(sender, "commands.dungeon-selected", "&aダンジョンプリセット {preset} / 深さ {depth} をセッション {session} に設定しました。",
                    "preset", templateSelection.presetName(),
                    "depth", maxDepth,
                    "session", session.sessionId());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            tellRaw(sender, ex.getMessage());
        }
        return true;
    }

    private boolean handleEnd(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.end")) {
            tell(sender, "commands.permission.end", "&cAziRougeセッションを終了する権限がありません。");
            return true;
        }
        if (args.length != 0) {
            tell(sender, "commands.usage.end", "&e使い方: /azirouge end");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.player-only", "&cこのコマンドはプレイヤーだけが実行できます。");
            return true;
        }

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            tell(sender, "session.error.not-in-session", "&cセッションに参加していません。");
            return true;
        }
        if (plugin.gameSessionManager().endSession(session)) {
            tell(sender, "commands.ended-world", "&aセッションワールド {world} を終了しました。", "world", session.world().getName());
        } else {
            tell(sender, "commands.end-world-failed", "&cセッションワールド {world} を完全に終了できませんでした。サーバーログを確認してください。", "world", session.world().getName());
        }
        return true;
    }

    private boolean handleGenerate(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.command.generate")) {
            tell(sender, "commands.permission.generate", "&cダンジョンを生成する権限がありません。");
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
            tellRaw(sender, ex.getMessage());
            return true;
        }

        String worldName = options.getOrDefault("world", defaults.worldName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            tell(sender, "commands.world-not-found", "&cワールドが見つかりません: {world}", "world", worldName);
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
            tell(sender, "commands.generated", "&aダンジョンを生成しました。seed={seed} ピース={pieces}/{target} 接続={connections} 敵予約={enemies} 深さ={depth} プリセット={preset}",
                    "seed", result.seed(),
                    "pieces", result.placedPieceCount(),
                    "target", result.targetPieceCount(),
                    "connections", result.connectionCount(),
                    "enemies", result.enemyReservations().size(),
                    "depth", depthOverride == null ? defaults.maxDepth() : depthOverride,
                    "preset", templateSelection.presetName());
        } catch (TemplateLoadException | SchematicPlacementException ex) {
            tell(sender, "commands.generate-failed", "&c生成に失敗しました: {reason}", "reason", ex.getMessage());
            plugin.getLogger().warning("Generation failed: " + ex.getMessage());
        }
        return true;
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        args = withoutConfirmFlag(args);
        if (!sender.hasPermission("azirouge.command.reload")) {
            tell(sender, "commands.permission.reload", "&cAziRougeをリロードする権限がありません。");
            return true;
        }
        if (args.length != 0) {
            tell(sender, "commands.usage.reload", "&e使い方: /azirouge reload");
            return true;
        }
        plugin.reloadPluginState();
        tell(sender, "commands.reloaded", "&aAziRougeの設定をリロードしました。");
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.debug")) {
            tell(sender, "commands.permission.debug", "&cデバッグログを切り替える権限がありません。");
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
        tell(sender, enabled ? "commands.debug-enabled" : "commands.debug-disabled",
                enabled ? "&aAziRougeのデバッグログを有効にしました。" : "&aAziRougeのデバッグログを無効にしました。");
        return true;
    }

    private boolean handleAuthor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("azirouge.command.author")) {
            tell(sender, "commands.permission.author", "&cゲーム内でテンプレートを編集する権限がありません。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "commands.author-player-only", "&cオーサリングコマンドはプレイヤーだけが実行できます。");
            return true;
        }
        if (args.length == 0) {
            tell(sender, "commands.usage.author", "&e使い方: /azirouge author <piece|entrance> ...");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "piece" -> handleAuthorPiece(player, Arrays.copyOfRange(args, 1, args.length));
            case "entrance" -> handleAuthorEntrance(player, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                tell(sender, "commands.unknown.author", "&c不明なオーサリング対象です: {target}", "target", args[0]);
                yield true;
            }
        };
    }

    private boolean handleAuthorPiece(Player player, String[] args) {
        if (args.length < 4 || !"upsert".equalsIgnoreCase(args[0])) {
            tell(player, "commands.usage.author-piece", "&e使い方: /azirouge author piece upsert <templateFile> <pieceId> <schematicPath> [weight]");
            tell(player, "commands.usage.author-piece-hint", "&eWorldEditでピース全体を選択してください。schematic origin は選択範囲の最小cornerに固定されます。");
            return true;
        }

        String templateFile = args[1];
        String pieceId = args[2];
        String schematicPath = args[3];
        double weight = args.length >= 5 ? parseDouble(args[4], 1.0D) : 1.0D;

        try {
            PieceAuthoringResult result = plugin.templateAuthoringService().upsertPiece(player, templateFile, pieceId, schematicPath, weight);
            tell(player, "commands.piece-saved", "&aピースを保存しました: {piece} テンプレート={template} origin={origin} bounds={bounds}",
                    "piece", pieceId,
                    "template", result.templateFile(),
                    "origin", format(result.origin()),
                    "bounds", format(result.bounds()));
        } catch (TemplateAuthoringException | SelectionLookupException ex) {
            tell(player, "commands.piece-failed", "&cピースの保存に失敗しました: {reason}", "reason", ex.getMessage());
        }
        return true;
    }

    private boolean handleAuthorEntrance(Player player, String[] args) {
        if (args.length == 0) {
            tell(player, "commands.usage.author-entrance", "&e使い方: /azirouge author entrance <upsert|remove> ...");
            return true;
        }

        if ("upsert".equalsIgnoreCase(args[0])) {
            if (args.length < 5) {
                tell(player, "commands.usage.author-entrance-upsert", "&e使い方: /azirouge author entrance upsert <templateFile> <pieceId> <entranceId> <facing>");
                tell(player, "commands.usage.author-entrance-hint", "&eWorldEditで入口面を選択してください。piece upsert で保存した最小cornerを自動で使います。");
                return true;
            }
            Direction facing;
            try {
                facing = Direction.valueOf(args[4].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                tell(player, "commands.facing-invalid", "&c向きは NORTH, EAST, SOUTH, WEST のいずれかです。");
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
                tell(player, "commands.entrance-saved", "&a入口を保存しました: {entrance} 向き={facing} origin={origin} plane={plane}",
                        "entrance", args[3],
                        "facing", result.facing(),
                        "origin", format(result.origin()),
                        "plane", format(result.plane()));
            } catch (TemplateAuthoringException | SelectionLookupException ex) {
                tell(player, "commands.entrance-failed", "&c入口の保存に失敗しました: {reason}", "reason", ex.getMessage());
            }
            return true;
        }

        if ("remove".equalsIgnoreCase(args[0])) {
            if (args.length < 4) {
                tell(player, "commands.usage.author-entrance-remove", "&e使い方: /azirouge author entrance remove <templateFile> <pieceId> <entranceId>");
                return true;
            }
            try {
                plugin.templateAuthoringService().removeEntrance(args[1], args[2], args[3]);
                tell(player, "commands.entrance-removed", "&a入口を削除しました: {entrance}", "entrance", args[3]);
            } catch (TemplateAuthoringException ex) {
                tell(player, "commands.entrance-remove-failed", "&c入口の削除に失敗しました: {reason}", "reason", ex.getMessage());
            }
            return true;
        }

        tell(player, "commands.unknown.entrance-action", "&c不明な入口操作です: {action}", "action", args[0]);
        return true;
    }

    private String[] withoutConfirmFlag(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !CONFIRM_FLAG.equalsIgnoreCase(arg))
                .toArray(String[]::new);
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

    private void tell(CommandSender sender, String key, String fallback, Object... replacements) {
        sender.sendMessage(plugin.messages().prefixed(key, fallback, replacements));
    }

    private void tellRaw(CommandSender sender, String message) {
        sender.sendMessage(plugin.messages().prefix() + message);
    }

    private void sendCopyableSessionId(CommandSender sender, GameSession session) {
        sender.sendMessage(Component.text("[Azirouge] ", NamedTextColor.GOLD)
                .append(Component.text(plugin.messages().text("session.id-label", "セッションID: "), NamedTextColor.YELLOW))
                .append(Component.text(session.sessionId(), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(session.sessionId())))
                .append(Component.text(plugin.messages().text("session.click-to-copy", " (クリックでコピー)"), NamedTextColor.GRAY)));
    }

    private void reportSessionCreationFailure(CommandSender sender, String key, String fallback, Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        tell(sender, key, fallback, "reason", reason);
        plugin.getLogger().warning(plugin.messages().format(key, fallback, "reason", reason));
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
                throw new IllegalArgumentException(plugin.messages().format(
                        "commands.unknown-preset",
                        "&c不明なテンプレートプリセットです: {preset}。利用可能: {presets}",
                        "preset", presetName,
                        "presets", String.join(", ", templatePresetNames())
                ));
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
