package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.RoundTimingSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
    private static final int CHECK_INTERVAL_TICKS = 10;

    private final AziRouge plugin;
    private final GameSessionManager sessionManager;
    private final Map<String, Integer> majoritySleepingTicks = new HashMap<>();
    private final Map<UUID, TransitionCause> requestedCauses = new HashMap<>();
    private final Set<String> scheduledTransitions = new HashSet<>();
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
        requestedCauses.clear();
        scheduledTransitions.clear();
        forcingSleep.clear();
    }

    public void beginRound(GameSession session) {
        configureWorld(session.world());
        majoritySleepingTicks.remove(session.sessionId());
        scheduledTransitions.remove(session.sessionId());
        for (Player player : session.world().getPlayers()) {
            if (player.isSleeping()) {
                player.wakeup(false);
            }
        }
        session.world().setTime(plugin.settings().roundTiming().startTimeTicks());
    }

    public void endRound(GameSession session) {
        majoritySleepingTicks.remove(session.sessionId());
        scheduledTransitions.remove(session.sessionId());
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
            player.sendMessage(plugin.messages().prefixed("sleep.home-only", "&cホームへ帰還してからベッドに入ってください。"));
            return;
        }

        if (player.getWorld().isDayTime()) {
            player.getWorld().setStorm(true);
            player.getWorld().setThundering(true);
        }
        event.setUseBed(Event.Result.ALLOW);
        plugin.journeyDisplayService().showBedHint(player);
        if (forcingSleep.contains(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (activeSession(player) != session || player.isSleeping()
                    || !forcingSleep.add(player.getUniqueId())) {
                return;
            }
            try {
                player.sleep(event.getBed().getLocation(), true);
            } finally {
                forcingSleep.remove(player.getUniqueId());
            }
        });
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
        TransitionCause cause = requestedCauses.getOrDefault(event.getWorld().getUID(), TransitionCause.SLEEP);
        Set<UUID> sleepingPlayers = sleepingPlayers(session);
        scheduleTransition(session, cause == TransitionCause.MIDNIGHT, sleepingPlayers);
    }

    private void tick() {
        for (GameSession session : sessionManager.sessions()) {
            if (session.state() != SessionState.IN_ROUND || session.roundState() != RoundState.ACTIVE
                    || session.isBossBattleActive() || scheduledTransitions.contains(session.sessionId())) {
                majoritySleepingTicks.remove(session.sessionId());
                continue;
            }

            if (remainingTicks(session) == 0L) {
                requestMorning(session, TransitionCause.MIDNIGHT);
                continue;
            }

            int alive = session.alivePlayers().size();
            int sleeping = sleepingPlayers(session).size();
            if (alive <= 0 || sleeping <= 0) {
                majoritySleepingTicks.remove(session.sessionId());
                continue;
            }
            if (RoundSleepPolicy.allSleeping(alive, sleeping)) {
                requestMorning(session, TransitionCause.SLEEP);
                continue;
            }

            int requiredPercentage = plugin.settings().roundTiming().minimumSleepingPercentage();
            if (!RoundSleepPolicy.minimumSleeping(alive, sleeping, requiredPercentage)) {
                majoritySleepingTicks.remove(session.sessionId());
                continue;
            }
            int elapsedTicks = majoritySleepingTicks.merge(
                    session.sessionId(),
                    CHECK_INTERVAL_TICKS,
                    Integer::sum
            );
            int waitTicks = plugin.settings().roundTiming().sleepDelaySeconds() * 20;
            int remainingSeconds = Math.max(0, (waitTicks - elapsedTicks + 19) / 20);
            showSleepCountdown(session, sleeping, alive, remainingSeconds);
            if (elapsedTicks >= waitTicks) {
                requestMorning(session, TransitionCause.SLEEP);
            }
        }
    }

    private void requestMorning(GameSession session, TransitionCause cause) {
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

        TimeSkipEvent event = new TimeSkipEvent(world, TimeSkipEvent.SkipReason.NIGHT_SKIP, skipAmount);
        requestedCauses.put(world.getUID(), cause);
        try {
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                world.setFullTime(world.getFullTime() + event.getSkipAmount());
            } else if (cause == TransitionCause.MIDNIGHT) {
                world.setTime(start);
                scheduleTransition(session, true, sleepingPlayers(session));
            }
        } finally {
            requestedCauses.remove(world.getUID());
        }
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

    private Set<UUID> sleepingPlayers(GameSession session) {
        Set<UUID> sleeping = new HashSet<>();
        for (UUID playerId : session.alivePlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isSleeping() && isInHomeArea(session, player.getLocation())) {
                sleeping.add(playerId);
            }
        }
        return Set.copyOf(sleeping);
    }

    private void showSleepCountdown(GameSession session, int sleeping, int alive, int remainingSeconds) {
        String message = plugin.messages().format(
                "sleep.countdown",
                "&e就寝中 {sleeping}/{alive} - 翌朝まで {seconds}秒",
                "sleeping", sleeping,
                "alive", alive,
                "seconds", remainingSeconds
        );
        Component component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand()
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
        world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
    }

    private long elapsedSince(long start, long current) {
        return Math.floorMod(current - start, DAY_TICKS);
    }

    private enum TransitionCause {
        SLEEP,
        MIDNIGHT
    }
}
