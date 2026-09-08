package net.azisaba.aziRouge.statistics;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.DatabaseSettings;
import net.azisaba.aziRouge.config.LeaderboardSettings;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StatisticsService {
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 30_000L;
    private static final long RECONNECT_INTERVAL_MILLIS = 30_000L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;
    private static final long TERMINATION_TIMEOUT_SECONDS = 1L;
    private static final int MAX_QUEUED_OPERATIONS = 2_048;

    private final AziRouge plugin;
    private final ExecutorService executor;
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);
    private final AtomicLong lastFailureLogMillis = new AtomicLong(Long.MIN_VALUE);
    private volatile StatisticsDatabase database;
    private volatile DatabaseSettings configuredSettings;
    private volatile boolean available;
    private volatile long circuitOpenUntilMillis;
    private long lastReconnectAttemptMillis = Long.MIN_VALUE;

    public StatisticsService(AziRouge plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_OPERATIONS),
                new StatisticsThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<Void> reload() {
        DatabaseSettings settings = plugin.settings().database();
        return submitLifecycle(() -> replaceDatabase(settings));
    }

    public boolean isAvailable() {
        return available;
    }

    public CompletableFuture<Void> recordSessionJoin(UUID runId, Player player) {
        PlayerSnapshot snapshot = PlayerSnapshot.from(player);
        Instant now = Instant.now();
        return submitDatabase("record session join", database -> {
            database.recordSessionJoin(requireRunId(runId), snapshot, now);
            return null;
        }, null);
    }

    public CompletableFuture<Void> recordSessionExit(UUID runId, UUID playerId, ExitReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Instant now = Instant.now();
        return submitDatabase("record session exit", database -> {
            database.recordSessionExit(requireRunId(runId), playerId, reason, now);
            return null;
        }, null);
    }

    public CompletableFuture<Void> recordRoundReached(
            UUID runId,
            Collection<PlayerSnapshot> players,
            int round,
            int configuredMaxDepth
    ) {
        requirePositive(round, "round");
        requirePositive(configuredMaxDepth, "configuredMaxDepth");
        List<PlayerSnapshot> snapshots = List.copyOf(Objects.requireNonNull(players, "players"));
        Instant now = Instant.now();
        return submitDatabase("record round reached", database -> {
            database.recordRoundReached(requireRunId(runId), snapshots, round, configuredMaxDepth, now);
            return null;
        }, null);
    }

    public CompletableFuture<Void> recordDeath(UUID runId, Player player, UUID deathEventId) {
        return recordCounter(runId, player, deathEventId, StatisticsDatabase.CounterEvent.DEATH, 1L, "record death");
    }

    public CompletableFuture<Void> recordGameOver(UUID runId) {
        Instant now = Instant.now();
        return submitDatabase("record game over", database -> {
            database.recordGameOver(requireRunId(runId), now);
            return null;
        }, null);
    }

    public CompletableFuture<Void> recordMaxDepth(UUID runId, Player player, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0");
        }
        PlayerSnapshot snapshot = PlayerSnapshot.from(player);
        Instant now = Instant.now();
        return submitDatabase("record max depth", database -> {
            database.recordMaxDepth(requireRunId(runId), snapshot, depth, now);
            return null;
        }, null);
    }

    public CompletableFuture<Void> recordMobKill(UUID runId, Player player, UUID mobId) {
        return recordCounter(runId, player, mobId, StatisticsDatabase.CounterEvent.MOB_KILL, 1L, "record mob kill");
    }

    public CompletableFuture<Void> recordChestOpened(UUID runId, Player player, UUID chestId) {
        return recordCounter(runId, player, chestId, StatisticsDatabase.CounterEvent.CHEST_OPENED, 1L, "record chest opened");
    }

    public CompletableFuture<Void> recordTreasure(UUID runId, Player player, UUID treasureId) {
        return recordCounter(runId, player, treasureId, StatisticsDatabase.CounterEvent.TREASURE, 1L, "record treasure");
    }

    public CompletableFuture<Void> recordSale(UUID runId, Player player, long amount, UUID saleId) {
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        return recordCounter(runId, player, saleId, StatisticsDatabase.CounterEvent.SALE, amount, "record sale");
    }

    public CompletableFuture<Map<RankingPeriod, List<RankingEntry>>> rankings() {
        LeaderboardSettings settings = plugin.settings().leaderboard();
        Instant now = Instant.now();
        return submitDatabase(
                "load rankings",
                database -> database.rankings(settings.timezone(), settings.topSize(), now),
                emptyRankings()
        );
    }

    public CompletableFuture<Optional<PlayerStatistics>> playerStatistics(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return submitDatabase(
                "load player statistics",
                database -> database.playerStatistics(playerId),
                Optional.empty()
        );
    }

    public CompletableFuture<Optional<PlayerStatistics>> playerStatistics(String playerName) {
        String normalizedName = Objects.requireNonNull(playerName, "playerName").trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 16) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return submitDatabase(
                "load player statistics by name",
                database -> database.playerStatistics(normalizedName),
                Optional.empty()
        );
    }

    public void shutdown() {
        if (!acceptingTasks.compareAndSet(true, false)) {
            return;
        }
        available = false;
        CompletableFuture<Void> closeFuture = submitLifecycleInternal(this::closeDatabase);
        boolean closedCleanly = false;
        try {
            closeFuture.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            closedCleanly = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while flushing AziRouge statistics.");
        } catch (ExecutionException | TimeoutException ex) {
            plugin.getLogger().warning("Could not flush AziRouge statistics cleanly: " + rootMessage(ex));
        } finally {
            if (closedCleanly) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
                closeDatabase();
            }
            try {
                if (!executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private CompletableFuture<Void> recordCounter(
            UUID runId,
            Player player,
            UUID eventId,
            StatisticsDatabase.CounterEvent event,
            long value,
            String operation
    ) {
        PlayerSnapshot snapshot = PlayerSnapshot.from(player);
        Objects.requireNonNull(eventId, "eventId");
        Instant now = Instant.now();
        return submitDatabase(operation, database -> {
            database.recordCounterEvent(requireRunId(runId), snapshot, eventId, event, value, now);
            return null;
        }, null);
    }

    private void replaceDatabase(DatabaseSettings settings) {
        configuredSettings = settings;
        closeDatabase();
        if (!settings.enabled()) {
            circuitOpenUntilMillis = 0L;
            plugin.getLogger().info("AziRouge statistics are disabled by configuration.");
            return;
        }
        lastReconnectAttemptMillis = System.currentTimeMillis();
        try {
            database = StatisticsDatabase.open(settings);
            available = true;
            circuitOpenUntilMillis = 0L;
            plugin.getLogger().info("AziRouge statistics database is ready.");
        } catch (Exception ex) {
            database = null;
            available = false;
            circuitOpenUntilMillis = System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS;
            logFailure("initialize statistics database", ex);
        }
    }

    private void closeDatabase() {
        StatisticsDatabase current = database;
        database = null;
        available = false;
        if (current != null) {
            current.close();
        }
    }

    private <T> CompletableFuture<T> submitDatabase(
            String operation,
            DatabaseOperation<T> task,
            T unavailableValue
    ) {
        if (!acceptingTasks.get()) {
            return CompletableFuture.completedFuture(unavailableValue);
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (System.currentTimeMillis() < circuitOpenUntilMillis) {
                    return unavailableValue;
                }
                StatisticsDatabase current = database;
                if (current == null) {
                    current = tryReconnect();
                }
                if (current == null) {
                    return unavailableValue;
                }
                try {
                    T value = task.execute(current);
                    available = true;
                    circuitOpenUntilMillis = 0L;
                    return value;
                } catch (Exception ex) {
                    available = false;
                    circuitOpenUntilMillis = System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS;
                    logFailure(operation, ex);
                    throw new StatisticsOperationException(operation, ex);
                }
            }, executor);
        } catch (RejectedExecutionException ex) {
            logFailure(operation, ex);
            return CompletableFuture.completedFuture(unavailableValue);
        }
    }

    private StatisticsDatabase tryReconnect() {
        DatabaseSettings settings = configuredSettings;
        if (settings == null || !settings.enabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (lastReconnectAttemptMillis != Long.MIN_VALUE
                && now - lastReconnectAttemptMillis < RECONNECT_INTERVAL_MILLIS) {
            return null;
        }
        lastReconnectAttemptMillis = now;
        replaceDatabase(settings);
        return database;
    }

    private CompletableFuture<Void> submitLifecycle(Runnable action) {
        if (!acceptingTasks.get()) {
            return CompletableFuture.completedFuture(null);
        }
        return submitLifecycleInternal(action);
    }

    private CompletableFuture<Void> submitLifecycleInternal(Runnable action) {
        try {
            return CompletableFuture.runAsync(action, executor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private void logFailure(String operation, Throwable error) {
        long now = System.currentTimeMillis();
        long previous = lastFailureLogMillis.get();
        if (previous != Long.MIN_VALUE && now - previous < FAILURE_LOG_INTERVAL_MILLIS) {
            return;
        }
        if (lastFailureLogMillis.compareAndSet(previous, now)) {
            plugin.getLogger().warning("Failed to " + operation + ": " + rootMessage(error));
        }
    }

    private static Map<RankingPeriod, List<RankingEntry>> emptyRankings() {
        Map<RankingPeriod, List<RankingEntry>> result = new EnumMap<>(RankingPeriod.class);
        for (RankingPeriod period : RankingPeriod.values()) {
            result.put(period, List.of());
        }
        return Map.copyOf(result);
    }

    private static UUID requireRunId(UUID runId) {
        return Objects.requireNonNull(runId, "runId");
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1");
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface DatabaseOperation<T> {
        T execute(StatisticsDatabase database) throws Exception;
    }

    private static final class StatisticsThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "AziRouge-Statistics");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class StatisticsOperationException extends RuntimeException {
        private StatisticsOperationException(String operation, Throwable cause) {
            super("Failed to " + operation, cause);
        }
    }
}
