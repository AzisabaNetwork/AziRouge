package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SessionScoreboardService {
    private static final String OBJECTIVE_NAME = "azirouge";
    private static final String SERVER_ADDRESS = "azisaba.net";

    private final AziRouge plugin;
    private final GameSessionManager sessionManager;
    private final Set<UUID> displayedPlayers = new HashSet<>();
    private BukkitTask task;

    public SessionScoreboardService(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 1L, 20L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID playerId : Set.copyOf(displayedPlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                clear(player);
            }
        }
        displayedPlayers.clear();
    }

    private void updateAll() {
        Set<UUID> onlinePlayers = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());
            GameSession session = sessionManager.sessionForPlayer(player.getUniqueId()).orElse(null);
            if (session == null) {
                if (displayedPlayers.contains(player.getUniqueId())) {
                    clear(player);
                }
                continue;
            }
            show(player, session);
        }
        displayedPlayers.removeIf(playerId -> !onlinePlayers.contains(playerId));
    }

    private void show(Player player, GameSession session) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }

        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, "dummy", ChatColor.GOLD + "AziRouge");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        registerHiddenNameTagTeam(scoreboard, session);

        int maintenanceRound = session.state() == SessionState.IN_ROUND
                ? session.currentRound()
                : session.currentRound() + 1;
        long maintenance = plugin.economyService().maintenanceCostForRound(maintenanceRound);
        setLine(objective, ChatColor.DARK_GRAY.toString(), 8);
        setLine(objective, label("state", "State") + session.state().getDisplayName(), 7);
        setLine(objective, label("round", "Round") + session.currentRound(), 6);
        setLine(objective, label("money", "Money") + session.sharedBalance(), 5);
        setLine(objective, label("maintenance", "Maintenance") + maintenance, 4);
        setLine(objective, ChatColor.BLACK.toString(), 3);
        setLine(objective, label("goal", "Goal") + goal(session, player), 2);
        setLine(objective, label("next", "Next") + nextAction(session, player), 1);
        setLine(objective, ChatColor.AQUA + SERVER_ADDRESS, 0);

        player.setScoreboard(scoreboard);
        displayedPlayers.add(player.getUniqueId());
    }

    private void registerHiddenNameTagTeam(Scoreboard scoreboard, GameSession session) {
        Team team = scoreboard.registerNewTeam("azirouge_hidden");
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        for (UUID playerId : session.onlineMembers()) {
            Player member = Bukkit.getPlayer(playerId);
            if (member != null) {
                team.addEntry(member.getName());
            }
        }
    }

    private void clear(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null && hasOwnSidebar(player.getScoreboard())) {
            player.setScoreboard(manager.getMainScoreboard());
        }
        displayedPlayers.remove(player.getUniqueId());
    }

    private boolean hasOwnSidebar(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(DisplaySlot.SIDEBAR);
        return objective != null && OBJECTIVE_NAME.equals(objective.getName());
    }

    private void setLine(Objective objective, String text, int score) {
        objective.getScore(text).setScore(score);
    }

    private String label(String key, String fallback) {
        return ChatColor.YELLOW + plugin.messages().text("scoreboard." + key, fallback) + ": " + ChatColor.WHITE;
    }

    private String goal(GameSession session, Player player) {
        String suffix = objectiveSuffix(session, player);
        return plugin.messages().text("scoreboard.goals." + suffix, fallbackGoal(session, player));
    }

    private String nextAction(GameSession session, Player player) {
        String suffix = objectiveSuffix(session, player);
        return plugin.messages().text("scoreboard.next-actions." + suffix, fallbackNextAction(session, player));
    }

    private String objectiveSuffix(GameSession session, Player player) {
        return switch (session.state()) {
            case LOBBY -> "lobby";
            case BETWEEN_ROUNDS -> "between-rounds";
            case IN_ROUND -> isInHomeArea(session, player) ? "in-round-home" : "in-round-dungeon";
            case GAME_OVER -> "game-over";
            case CLOSING -> "closing";
        };
    }

    private boolean isInHomeArea(GameSession session, Player player) {
        return player.getWorld().getUID().equals(session.world().getUID())
                && session.homeArea().contains(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ()
        );
    }

    private String fallbackGoal(GameSession session, Player player) {
        return switch (session.state()) {
            case LOBBY -> "Gather party";
            case BETWEEN_ROUNDS -> "Prepare";
            case IN_ROUND -> isInHomeArea(session, player) ? "Bring loot back" : "Loot and return";
            case GAME_OVER -> "Review result";
            case CLOSING -> "Closing";
        };
    }

    private String fallbackNextAction(GameSession session, Player player) {
        return switch (session.state()) {
            case LOBBY -> "Start round";
            case BETWEEN_ROUNDS -> "Shop/start";
            case IN_ROUND -> isInHomeArea(session, player) ? "Enter portal" : "Find portal";
            case GAME_OVER -> "Leave session";
            case CLOSING -> "Wait";
        };
    }
}
