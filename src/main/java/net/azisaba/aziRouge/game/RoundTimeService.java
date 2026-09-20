package net.azisaba.aziRouge.game;

import io.papermc.paper.event.player.PlayerDeepSleepEvent;
import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.RoundTimingSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RoundTimeService implements Listener {
    private static final long DAY_TICKS = 24_000L;
    private static final long NIGHT_TICKS = 13_000L;
    private static final long SLEEP_DELAY_TICKS = 3L;
    private static final int CHECK_INTERVAL_TICKS = 10;

    private final AziRouge plugin;
    private final GameSessionManager sessionManager;
    private final Map<String, Integer> majoritySleepingTicks = new HashMap<>();
    private final Map<String, Integer> deadlineWarningHours = new HashMap<>();
    private final Set<String> scheduledTransitions = new HashSet<>();
    private final Set<UUID> deeplySleepingPlayers = new HashSet<>();
    private final Set<UUID> forcingSleep = new HashSet<>();
    private BukkitTask task;

    public RoundTimeService(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        majoritySleepingTicks.clear();
        deadlineWarningHours.clear();
        scheduledTransitions.clear();
        deeplySleepingPlayers.clear();
        forcingSleep.clear();
    }

    public void prepareWorld(World world) {
        configureWorld(world);
        world.setTime(plugin.settings().roundTiming().startTimeTicks());
    }

    public void beginRound(GameSession session) {
        prepareWorld(session.world());
        majoritySleepingTicks.remove(session.sessionId());
        deadlineWarningHours.remove(session.sessionId());
        scheduledTransitions.remove(session.sessionId());
        deeplySleepingPlayers.removeAll(session.members());
        for (Player player : session.world().getPlayers()) {
            if (player.isSleeping()) {
                player.wakeup(false);
            }
        }
    }

    public void endRound(GameSession session) {
        majoritySleepingTicks.remove(session.sessionId());
        deadlineWarningHours.remove(session.sessionId());
        scheduledTransitions.remove(session.sessionId());
        deeplySleepingPlayers.removeAll(session.members());
        forcingSleep.removeAll(session.members());
    }

    public long remainingTicks(GameSession session) {
        RoundTimingSettings settings = plugin.settings().roundTiming();
        long elapsed = elapsedSince(settings.startTimeTicks(), session.world().getTime());
        long deadline = elapsedSince(settings.startTimeTicks(), settings.deadlineTimeTicks());
        return Math.max(0L, deadline - elapsed);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        GameSession session = activeSession(player);
        if (session == null) {
            return;
        }
        if (!isInHomeArea(session, player.getLocation())) {
            event.setUseBed(Event.Result.DENY);
            player.sendMessage(plugin.messages().prefixed("sleep.home-only", "&cベッドはホームに戻ってから。"));
            return;
        }

        plugin.journeyDisplayService().showBedHint(player);

        if (player.getWorld().isDayTime()) {
            event.setUseBed(Event.Result.DENY);
            Location bedLocation = event.getBed().getLocation();
            int round = session.currentRound();
            Set<UUID> alivePlayers = Set.copyOf(session.alivePlayers());
            Set<UUID> playersAtHome = alivePlayersAtHome(session);
            plugin.confirmationService().request(
                    player,
                    plugin.messages().text("sleep.confirm-night-impact", "いまは昼だ。夜にして寝ると、探索中の仲間の時間も進む。"),
                    () -> sleepAtNight(player, session, round, alivePlayers, playersAtHome, bedLocation)
            );
            return;
        }
        event.setUseBed(Event.Result.ALLOW);
        scheduleSleep(player, session, event.getBed().getLocation());
    }

    private void sleepAtNight(Player player, GameSession session, int round, Set<UUID> alivePlayers, Set<UUID> playersAtHome, Location bedLocation) {
        if (activeSession(player) != session || session.currentRound() != round || !session.world().isDayTime()
                || !alivePlayers.equals(session.alivePlayers())
                || !playersAtHome.equals(alivePlayersAtHome(session))
                || !isInHomeArea(session, player.getLocation())
                || player.getLocation().distanceSquared(bedLocation) > 16.0D
                || !(bedLocation.getBlock().getBlockData() instanceof Bed)) {
            player.sendMessage(plugin.messages().prefixed("sleep.changed", "&7状況が変わったので、就寝をやめた。"));
            return;
        }
        session.world().setTime(NIGHT_TICKS);
        scheduleSleep(player, session, bedLocation);
    }

    private void scheduleSleep(Player player, GameSession session, Location bedLocation) {
        if (forcingSleep.contains(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (activeSession(player) != session || player.isSleeping()
                    || !forcingSleep.add(player.getUniqueId())) {
                return;
            }
            try {
                player.sleep(bedLocation, true);
            } finally {
                forcingSleep.remove(player.getUniqueId());
            }
        }, SLEEP_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeepSleep(PlayerDeepSleepEvent event) {
        Player player = event.getPlayer();
        GameSession session = activeSession(player);
        if (session != null && isInHomeArea(session, player.getLocation())) {
            deeplySleepingPlayers.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        deeplySleepingPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkipped(TimeSkipEvent event) {
        if (event.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            return;
        }
        GameSession session = sessionManager.sessionForWorld(event.getWorld()).orElse(null);
        if (session == null || session.state() != SessionState.IN_ROUND || session.isBossBattleActive()) {
            return;
        }
        event.getWorld().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
        scheduleTransition(session, false, deeplySleepingPlayers(session));
    }

    private void tick() {
        for (GameSession session : sessionManager.sessions()) {
            if (session.state() != SessionState.IN_ROUND || session.roundState() != RoundState.ACTIVE
                    || session.isBossBattleActive() || scheduledTransitions.contains(session.sessionId())) {
                majoritySleepingTicks.remove(session.sessionId());
                continue;
            }

            long remainingTicks = remainingTicks(session);
            showDeadlineWarning(session, remainingTicks);
            if (remainingTicks == 0L) {
                forceMorning(session);
                continue;
            }

            int alive = session.alivePlayers().size();
            int sleeping = deeplySleepingPlayers(session).size();
            if (alive <= 0 || sleeping <= 0) {
                session.world().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
                majoritySleepingTicks.remove(session.sessionId());
                continue;
            }
            if (RoundSleepPolicy.allSleeping(alive, sleeping)) {
                continue;
            }

            int requiredPercentage = plugin.settings().roundTiming().minimumSleepingPercentage();
            if (!RoundSleepPolicy.minimumSleeping(alive, sleeping, requiredPercentage)) {
                session.world().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
                majoritySleepingTicks.remove(session.sessionId());
                showSleepStatus(
                        session,
                        "sleep.waiting-players",
                        "&e夜を越すには、あと {players}人眠る必要がある",
                        "players", RoundSleepPolicy.requiredSleeping(alive, requiredPercentage) - sleeping
                );
                continue;
            }
            int elapsedTicks = majoritySleepingTicks.merge(
                    session.sessionId(),
                    CHECK_INTERVAL_TICKS,
                    Integer::sum
            );
            int waitTicks = plugin.settings().roundTiming().sleepDelaySeconds() * 20;
            int remainingSeconds = Math.max(0, (waitTicks - elapsedTicks + 19) / 20);
            showSleepStatus(
                    session,
                    "sleep.skip-countdown",
                    "&eあと {seconds}秒で夜が明ける",
                    "seconds", remainingSeconds
            );
            if (elapsedTicks >= waitTicks) {
                session.world().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, requiredPercentage);
            }
        }
    }

    private void forceMorning(GameSession session) {
        if (scheduledTransitions.contains(session.sessionId())) {
            return;
        }
        World world = session.world();
        long current = Math.floorMod(world.getTime(), DAY_TICKS);
        long start = plugin.settings().roundTiming().startTimeTicks();
        long skipAmount = Math.floorMod(start - current, DAY_TICKS);
        if (skipAmount == 0L) {
            skipAmount = DAY_TICKS;
        }
        world.setFullTime(world.getFullTime() + skipAmount);
        scheduleTransition(session, true, deeplySleepingPlayers(session));
    }

    private void scheduleTransition(GameSession session, boolean midnight, Set<UUID> sleepingPlayers) {
        if (!scheduledTransitions.add(session.sessionId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                sessionManager.finishTimedRound(session, midnight, sleepingPlayers);
            } finally {
                majoritySleepingTicks.remove(session.sessionId());
                scheduledTransitions.remove(session.sessionId());
            }
        });
    }

    private GameSession activeSession(Player player) {
        GameSession session = sessionManager.sessionForPlayer(player.getUniqueId()).orElse(null);
        return session != null
                && session.state() == SessionState.IN_ROUND
                && session.roundState() == RoundState.ACTIVE
                && !session.isBossBattleActive()
                && session.alivePlayers().contains(player.getUniqueId())
                && player.getWorld().getUID().equals(session.world().getUID())
                ? session
                : null;
    }

    private Set<UUID> deeplySleepingPlayers(GameSession session) {
        Set<UUID> sleeping = new HashSet<>();
        for (UUID playerId : session.alivePlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && deeplySleepingPlayers.contains(playerId)
                    && player.isDeeplySleeping() && isInHomeArea(session, player.getLocation())) {
                sleeping.add(playerId);
            }
        }
        return Set.copyOf(sleeping);
    }

    private Set<UUID> alivePlayersAtHome(GameSession session) {
        Set<UUID> players = new HashSet<>();
        for (UUID playerId : session.alivePlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && isInHomeArea(session, player.getLocation())) {
                players.add(playerId);
            }
        }
        return Set.copyOf(players);
    }

    private void showDeadlineWarning(GameSession session, long remainingTicks) {
        int hours = RoundClock.deadlineWarningHours(remainingTicks);
        int previous = deadlineWarningHours.getOrDefault(session.sessionId(), 0);
        if (hours == 0 || hours == previous || (hours == 3 && previous == 1)) {
            return;
        }
        deadlineWarningHours.put(session.sessionId(), hours);
        boolean urgent = hours == 1;
        String chat = plugin.messages().prefixed(
                urgent ? "sleep.deadline-one-hour" : "sleep.deadline-three-hours",
                urgent
                        ? "&c0時まであと1時間。探索を切り上げて帰れ。"
                        : "&e0時まであと3時間。帰り道を見ておけ。"
        );
        String title = plugin.messages().text(
                urgent ? "sleep.title.one-hour" : "sleep.title.three-hours",
                urgent ? "&cあと1時間" : "&e日没が近い"
        );
        String subtitle = plugin.messages().text(
                urgent ? "sleep.subtitle.one-hour" : "sleep.subtitle.three-hours",
                urgent ? "&c切り上げて家に戻れ" : "&70時までにベッドへ"
        );
        for (UUID playerId : session.alivePlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(chat);
                player.sendTitle(title, subtitle, 10, 55, 15);
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, urgent ? 1.0F : 0.7F, urgent ? 1.35F : 0.75F);
            }
        }
    }

    private void showSleepStatus(GameSession session, String key, String fallback, Object... replacements) {
        String message = plugin.messages().format(
                key,
                fallback,
                replacements
        );
        Component component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection()
                .deserialize(message);
        for (UUID playerId : session.alivePlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendActionBar(component);
            }
        }
    }

    private boolean isInHomeArea(GameSession session, Location location) {
        return location.getWorld() != null
                && location.getWorld().getUID().equals(session.world().getUID())
                && session.homeArea().contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void configureWorld(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, true);
        world.setGameRule(GameRules.ADVANCE_WEATHER, true);
        world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
    }

    private long elapsedSince(long start, long current) {
        return Math.floorMod(current - start, DAY_TICKS);
    }
}
