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

        setLine(objective, ChatColor.DARK_GRAY.toString(), 5);
        setLine(objective, ChatColor.YELLOW + "State: " + ChatColor.WHITE + session.state().getDisplayName(), 4);
        setLine(objective, ChatColor.YELLOW + "Round: " + ChatColor.WHITE + session.currentRound(), 3);
        setLine(objective, ChatColor.YELLOW + "Money: " + ChatColor.WHITE + session.sharedBalance(), 2);
        setLine(objective, ChatColor.BLACK.toString(), 1);
        setLine(objective, ChatColor.AQUA + SERVER_ADDRESS, 0);

        player.setScoreboard(scoreboard);
        displayedPlayers.add(player.getUniqueId());
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
}
