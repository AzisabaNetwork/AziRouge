package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
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
        sessionManager.sessions().forEach(this::showAngerParticles);
    }

    private void showAngerParticles(GameSession session) {
        if (session.consecutiveQuotaMisses() <= 0) {
            return;
        }
        for (Villager villager : session.world().getEntitiesByClass(Villager.class)) {
            Location location = villager.getLocation();
            if (!session.homeArea().contains(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                continue;
            }
            Location above = location.add(0.0D, 2.1D, 0.0D);
            for (UUID playerId : session.onlineMembers()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.getWorld().getUID().equals(session.world().getUID())) {
                    player.spawnParticle(Particle.ANGRY_VILLAGER, above, 4, 0.3D, 0.2D, 0.3D, 0.0D);
                }
            }
        }
    }

    private void show(Player player, GameSession session) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }

        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                OBJECTIVE_NAME,
                "dummy",
                plugin.messages().text("scoreboard.title", "AziRouge")
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        registerHiddenNameTagTeam(scoreboard, session);

        int quotaRound = DepartureGuard.dayToStart(session.currentRound());
        long quota = plugin.economyService().quotaForRound(quotaRound);
        long delivered = session.state() == SessionState.GAME_OVER || session.state() == SessionState.CLOSING
                ? 0L
                : plugin.economyService().deliveryValue(session);
        int maxMisses = plugin.settings().economy().quota().maxConsecutiveMisses();
        setLine(objective, ChatColor.DARK_GRAY.toString(), 11);
        setLine(objective, ChatColor.GOLD + plugin.messages().format(
                "scoreboard.day",
                "{round}日目",
                "round", session.currentRound()
        ), 9);
        setLine(objective, label("shared-money", "共有資金") + session.sharedBalance(), 8);
        setLine(objective, label("delivery-quota", "納品 / ノルマ") + delivered + " / " + quota, 7);
        setLine(objective, label("villager-anger", "村人の怒り") + AngerGauge.render(session.consecutiveQuotaMisses(), maxMisses), 6);
        String time = allAlivePlayersInDungeon(session) ? "??:??" : RoundClock.format(session.world().getTime());
        setLine(objective, label("time", "時刻") + time, 5);
        setLine(objective, ChatColor.BLACK.toString(), 4);
        Guidance guidance = guidance(session, player, delivered, quota);
        if (!guidance.goal().isBlank()) {
            setLine(objective, label("goal", "目標") + guidance.goal(), 3);
        }
        if (!guidance.next().isBlank()) {
            setLine(objective, label("next", "やること") + guidance.next(), 2);
        }
        setLine(objective, ChatColor.DARK_AQUA.toString(), 1);
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

    private boolean allAlivePlayersInDungeon(GameSession session) {
        if (session.state() != SessionState.IN_ROUND || session.alivePlayers().isEmpty()) {
            return false;
        }
        for (UUID playerId : session.alivePlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || isInHomeArea(session, player)) {
                return false;
            }
        }
        return true;
    }

    private boolean isInHomeArea(GameSession session, Player player) {
        return player.getWorld().getUID().equals(session.world().getUID())
                && session.homeArea().contains(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ()
        );
    }

    private Guidance guidance(GameSession session, Player player, long delivered, long quota) {
        if (session.state() == SessionState.GAME_OVER) {
            return new Guidance("", plugin.messages().text("scoreboard.next-actions.leave-session", "セッションを出る"));
        }
        if (session.state() == SessionState.LOBBY) {
            return new Guidance("", plugin.messages().text(
                    session.currentRound() == 0
                            ? "scoreboard.next-actions.start-first-round"
                            : "scoreboard.next-actions.choose-depth",
                    session.currentRound() == 0
                            ? "装備を整えてダンジョンに入ろう！"
                            : "深さを選んでダンジョンへ"
            ));
        }
        if (session.isBossBattleActive()) {
            return new Guidance(plugin.messages().text("scoreboard.goals.defeat-boss", "ボスを倒す"), "");
        }
        if (session.state() != SessionState.IN_ROUND || session.roundState() != RoundState.ACTIVE) {
            return Guidance.NONE;
        }
        if (!session.alivePlayers().contains(player.getUniqueId())) {
            return new Guidance("", plugin.messages().text("scoreboard.next-actions.wait-next-round", "翌日を待つ"));
        }
        if (!isInHomeArea(session, player)) {
            if (plugin.roundTimeService().remainingTicks(session) <= 3_000L) {
                return new Guidance(
                        plugin.messages().format(
                                "scoreboard.goals.return-before-midnight",
                                "{deadline}までに帰還",
                                "deadline", RoundClock.format(plugin.settings().roundTiming().deadlineTimeTicks())
                        ),
                        plugin.messages().text("scoreboard.next-actions.find-return", "帰還ポータルを探す")
                );
            }
            return Guidance.NONE;
        }
        if (!session.hasExploredThisRound(player.getUniqueId())) {
            return Guidance.NONE;
        }
        long missing = Math.max(0L, quota - delivered);
        if (missing > 0L) {
            return new Guidance(
                    plugin.messages().format("scoreboard.goals.quota-needed", "ノルマまであと {amount}", "amount", missing),
                    hasDeliverable(player)
                            ? plugin.messages().text("scoreboard.next-actions.deliver-treasure", "宝を納品する")
                            : ""
            );
        }
        return new Guidance(
                plugin.messages().text("scoreboard.goals.quota-achieved", "ノルマ達成"),
                player.isSleeping() ? "" : plugin.messages().text("scoreboard.next-actions.sleep", "ベッドで休む")
        );
    }

    private boolean hasDeliverable(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && plugin.settings().economy().sellPrices().containsKey(item.getType())) {
                return true;
            }
        }
        return false;
    }

    private record Guidance(String goal, String next) {
        private static final Guidance NONE = new Guidance("", "");
    }
}

final class AngerGauge {
    private static final int LENGTH = 19;

    private AngerGauge() {
    }

    static String render(int misses, int maximum) {
        int safeMaximum = Math.max(1, maximum);
        int angry = Math.clamp(misses, 0, safeMaximum);
        if (angry > 0 && angry >= safeMaximum - 1) {
            return "§c" + "|".repeat(LENGTH);
        }
        int filled = angry == 0 ? Math.ceilDiv(LENGTH, 3) : Math.ceilDiv(LENGTH * 2, 3);
        String color = angry == 0 ? "§a" : "§6";
        return color + "|".repeat(filled) + "§7" + "|".repeat(LENGTH - filled);
    }
}
