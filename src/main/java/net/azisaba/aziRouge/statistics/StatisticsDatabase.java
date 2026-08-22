package net.azisaba.aziRouge.statistics;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.azisaba.aziRouge.config.DatabaseSettings;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class StatisticsDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;

    private static final String CREATE_SCHEMA_VERSION = """
            CREATE TABLE IF NOT EXISTS azirouge_schema_version (
                singleton_id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
                version INT UNSIGNED NOT NULL,
                updated_at DATETIME(6) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_PLAYER_STATISTICS = """
            CREATE TABLE IF NOT EXISTS azirouge_player_statistics (
                player_uuid BINARY(16) NOT NULL PRIMARY KEY,
                player_name VARCHAR(16) NOT NULL,
                max_round INT UNSIGNED NOT NULL DEFAULT 0,
                total_rounds_reached BIGINT UNSIGNED NOT NULL DEFAULT 0,
                sessions_joined BIGINT UNSIGNED NOT NULL DEFAULT 0,
                deaths BIGINT UNSIGNED NOT NULL DEFAULT 0,
                game_overs BIGINT UNSIGNED NOT NULL DEFAULT 0,
                max_depth INT UNSIGNED NOT NULL DEFAULT 0,
                total_play_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
                longest_play_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
                mob_kills BIGINT UNSIGNED NOT NULL DEFAULT 0,
                chests_opened BIGINT UNSIGNED NOT NULL DEFAULT 0,
                treasures_collected BIGINT UNSIGNED NOT NULL DEFAULT 0,
                total_sales BIGINT UNSIGNED NOT NULL DEFAULT 0,
                early_leaves BIGINT UNSIGNED NOT NULL DEFAULT 0,
                disconnects BIGINT UNSIGNED NOT NULL DEFAULT 0,
                first_seen_at DATETIME(6) NOT NULL,
                last_seen_at DATETIME(6) NOT NULL,
                updated_at DATETIME(6) NOT NULL,
                KEY idx_azirouge_player_name (player_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_RUNS = """
            CREATE TABLE IF NOT EXISTS azirouge_runs (
                run_id BINARY(16) NOT NULL PRIMARY KEY,
                created_at DATETIME(6) NOT NULL,
                game_over_at DATETIME(6) NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_PLAYER_RUNS = """
            CREATE TABLE IF NOT EXISTS azirouge_player_runs (
                run_id BINARY(16) NOT NULL,
                player_uuid BINARY(16) NOT NULL,
                joined_at DATETIME(6) NOT NULL,
                active_since DATETIME(6) NULL,
                total_play_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
                last_exit_at DATETIME(6) NULL,
                last_exit_reason VARCHAR(32) NULL,
                PRIMARY KEY (run_id, player_uuid),
                KEY idx_azirouge_player_runs_player (player_uuid, joined_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_ROUND_REACHES = """
            CREATE TABLE IF NOT EXISTS azirouge_round_reaches (
                run_id BINARY(16) NOT NULL,
                player_uuid BINARY(16) NOT NULL,
                round_number INT UNSIGNED NOT NULL,
                configured_max_depth INT UNSIGNED NOT NULL,
                reached_at DATETIME(6) NOT NULL,
                PRIMARY KEY (run_id, player_uuid, round_number),
                KEY idx_azirouge_reached_period_score (reached_at, round_number, player_uuid),
                KEY idx_azirouge_reached_player_score (player_uuid, round_number, reached_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_STAT_EVENTS = """
            CREATE TABLE IF NOT EXISTS azirouge_stat_events (
                event_type VARCHAR(32) NOT NULL,
                event_id BINARY(16) NOT NULL,
                run_id BINARY(16) NOT NULL,
                player_uuid BINARY(16) NOT NULL,
                event_value BIGINT UNSIGNED NOT NULL,
                occurred_at DATETIME(6) NOT NULL,
                PRIMARY KEY (run_id, event_type, event_id, player_uuid),
                KEY idx_azirouge_events_player (player_uuid, occurred_at),
                KEY idx_azirouge_events_run (run_id, occurred_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String UPSERT_PLAYER = """
            INSERT INTO azirouge_player_statistics (
                player_uuid, player_name, first_seen_at, last_seen_at, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                last_seen_at = GREATEST(last_seen_at, VALUES(last_seen_at)),
                updated_at = GREATEST(updated_at, VALUES(updated_at))
            """;

    private final HikariDataSource dataSource;

    private StatisticsDatabase(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    static StatisticsDatabase open(DatabaseSettings settings) throws SQLException {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("AziRouge-Statistics");
        hikari.setJdbcUrl(settings.jdbcUrl());
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.maximumPoolSize());
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(settings.connectionTimeoutMillis());
        hikari.setInitializationFailTimeout(settings.connectionTimeoutMillis());
        hikari.setAutoCommit(true);
        hikari.setConnectionInitSql("SET time_zone = '+00:00'");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "128");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        HikariDataSource dataSource = new HikariDataSource(hikari);
        StatisticsDatabase database = new StatisticsDatabase(dataSource);
        try {
            database.migrate();
            return database;
        } catch (SQLException | RuntimeException ex) {
            dataSource.close();
            throw ex;
        }
    }

    void recordSessionJoin(UUID runId, PlayerSnapshot player, Instant occurredAt) throws SQLException {
        transaction(connection -> {
            ensureRun(connection, runId, occurredAt);
            upsertPlayer(connection, player, occurredAt);
            ensurePlayerRun(connection, runId, player.playerId(), occurredAt);
            return null;
        });
    }

    void recordSessionExit(UUID runId, UUID playerId, ExitReason reason, Instant occurredAt) throws SQLException {
        transaction(connection -> {
            LocalDateTime activeSince;
            long previousRunSeconds;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT active_since, total_play_seconds
                    FROM azirouge_player_runs
                    WHERE run_id = ? AND player_uuid = ?
                    FOR UPDATE
                    """)) {
                statement.setBytes(1, uuidBytes(runId));
                statement.setBytes(2, uuidBytes(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    activeSince = result.getObject("active_since", LocalDateTime.class);
                    previousRunSeconds = result.getLong("total_play_seconds");
                }
            }
            if (activeSince == null) {
                return null;
            }

            LocalDateTime endedAt = utcDateTime(occurredAt);
            long intervalSeconds = Math.max(0L, Duration.between(activeSince, endedAt).getSeconds());
            long runSeconds = saturatingAdd(previousRunSeconds, intervalSeconds);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE azirouge_player_runs
                    SET active_since = NULL,
                        total_play_seconds = ?,
                        last_exit_at = ?,
                        last_exit_reason = ?
                    WHERE run_id = ? AND player_uuid = ? AND active_since IS NOT NULL
                    """)) {
                statement.setLong(1, runSeconds);
                statement.setObject(2, endedAt);
                statement.setString(3, reason.name());
                statement.setBytes(4, uuidBytes(runId));
                statement.setBytes(5, uuidBytes(playerId));
                if (statement.executeUpdate() == 0) {
                    return null;
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE azirouge_player_statistics
                    SET total_play_seconds = total_play_seconds + ?,
                        longest_play_seconds = GREATEST(longest_play_seconds, ?),
                        early_leaves = early_leaves + ?,
                        disconnects = disconnects + ?,
                        last_seen_at = GREATEST(last_seen_at, ?),
                        updated_at = GREATEST(updated_at, ?)
                    WHERE player_uuid = ?
                    """)) {
                statement.setLong(1, intervalSeconds);
                statement.setLong(2, runSeconds);
                statement.setInt(3, reason == ExitReason.LEAVE ? 1 : 0);
                statement.setInt(4, reason == ExitReason.DISCONNECT ? 1 : 0);
                statement.setObject(5, endedAt);
                statement.setObject(6, endedAt);
                statement.setBytes(7, uuidBytes(playerId));
                statement.executeUpdate();
            }
            return null;
        });
    }

    void recordRoundReached(
            UUID runId,
            Collection<PlayerSnapshot> players,
            int round,
            int configuredMaxDepth,
            Instant occurredAt
    ) throws SQLException {
        Map<UUID, PlayerSnapshot> uniquePlayers = new LinkedHashMap<>();
        for (PlayerSnapshot player : players) {
            uniquePlayers.put(player.playerId(), player);
        }
        if (uniquePlayers.isEmpty()) {
            return;
        }

        transaction(connection -> {
            ensureRun(connection, runId, occurredAt);
            for (PlayerSnapshot player : uniquePlayers.values()) {
                upsertPlayer(connection, player, occurredAt);
                ensurePlayerRun(connection, runId, player.playerId(), occurredAt);
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT IGNORE INTO azirouge_round_reaches (
                            run_id, player_uuid, round_number, configured_max_depth, reached_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setBytes(1, uuidBytes(runId));
                    statement.setBytes(2, uuidBytes(player.playerId()));
                    statement.setInt(3, round);
                    statement.setInt(4, configuredMaxDepth);
                    statement.setObject(5, utcDateTime(occurredAt));
                    inserted = statement.executeUpdate();
                }
                if (inserted == 1) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE azirouge_player_statistics
                            SET max_round = GREATEST(max_round, ?),
                                total_rounds_reached = total_rounds_reached + 1,
                                last_seen_at = GREATEST(last_seen_at, ?),
                                updated_at = GREATEST(updated_at, ?)
                            WHERE player_uuid = ?
                            """)) {
                        statement.setInt(1, round);
                        statement.setObject(2, utcDateTime(occurredAt));
                        statement.setObject(3, utcDateTime(occurredAt));
                        statement.setBytes(4, uuidBytes(player.playerId()));
                        statement.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    void recordGameOver(UUID runId, Instant occurredAt) throws SQLException {
        transaction(connection -> {
            ensureRun(connection, runId, occurredAt);
            int changed;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE azirouge_runs
                    SET game_over_at = ?
                    WHERE run_id = ? AND game_over_at IS NULL
                    """)) {
                statement.setObject(1, utcDateTime(occurredAt));
                statement.setBytes(2, uuidBytes(runId));
                changed = statement.executeUpdate();
            }
            if (changed == 1) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE azirouge_player_statistics player_stats
                        INNER JOIN azirouge_player_runs player_run
                            ON player_run.player_uuid = player_stats.player_uuid
                        SET player_stats.game_overs = player_stats.game_overs + 1,
                            player_stats.last_seen_at = GREATEST(player_stats.last_seen_at, ?),
                            player_stats.updated_at = GREATEST(player_stats.updated_at, ?)
                        WHERE player_run.run_id = ?
                          AND (player_run.last_exit_reason IS NULL OR player_run.last_exit_reason <> 'LEAVE')
                        """)) {
                    statement.setObject(1, utcDateTime(occurredAt));
                    statement.setObject(2, utcDateTime(occurredAt));
                    statement.setBytes(3, uuidBytes(runId));
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    void recordMaxDepth(UUID runId, PlayerSnapshot player, int depth, Instant occurredAt) throws SQLException {
        transaction(connection -> {
            ensureRun(connection, runId, occurredAt);
            upsertPlayer(connection, player, occurredAt);
            ensurePlayerRun(connection, runId, player.playerId(), occurredAt);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE azirouge_player_statistics
                    SET max_depth = GREATEST(max_depth, ?),
                        last_seen_at = GREATEST(last_seen_at, ?),
                        updated_at = GREATEST(updated_at, ?)
                    WHERE player_uuid = ?
                    """)) {
                statement.setInt(1, depth);
                statement.setObject(2, utcDateTime(occurredAt));
                statement.setObject(3, utcDateTime(occurredAt));
                statement.setBytes(4, uuidBytes(player.playerId()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    void recordCounterEvent(
            UUID runId,
            PlayerSnapshot player,
            UUID eventId,
            CounterEvent event,
            long value,
            Instant occurredAt
    ) throws SQLException {
        transaction(connection -> {
            ensureRun(connection, runId, occurredAt);
            upsertPlayer(connection, player, occurredAt);
            ensurePlayerRun(connection, runId, player.playerId(), occurredAt);
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT IGNORE INTO azirouge_stat_events (
                        event_type, event_id, run_id, player_uuid, event_value, occurred_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, event.name());
                statement.setBytes(2, uuidBytes(eventId));
                statement.setBytes(3, uuidBytes(runId));
                statement.setBytes(4, uuidBytes(player.playerId()));
                statement.setLong(5, value);
                statement.setObject(6, utcDateTime(occurredAt));
                inserted = statement.executeUpdate();
            }
            if (inserted == 1) {
                String sql = "UPDATE azirouge_player_statistics SET " + event.columnName()
                        + " = " + event.columnName() + " + ?, last_seen_at = GREATEST(last_seen_at, ?), "
                        + "updated_at = GREATEST(updated_at, ?) WHERE player_uuid = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, value);
                    statement.setObject(2, utcDateTime(occurredAt));
                    statement.setObject(3, utcDateTime(occurredAt));
                    statement.setBytes(4, uuidBytes(player.playerId()));
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    Optional<PlayerStatistics> playerStatistics(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_name, max_round, total_rounds_reached, sessions_joined,
                            deaths, game_overs, max_depth, total_play_seconds, longest_play_seconds,
                            mob_kills, chests_opened, treasures_collected, total_sales,
                            early_leaves, disconnects, first_seen_at, last_seen_at
                     FROM azirouge_player_statistics
                     WHERE player_uuid = ?
                     """)) {
            statement.setBytes(1, uuidBytes(playerId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerStatistics(
                        playerId,
                        result.getString("player_name"),
                        result.getInt("max_round"),
                        result.getLong("total_rounds_reached"),
                        result.getLong("sessions_joined"),
                        result.getLong("deaths"),
                        result.getLong("game_overs"),
                        result.getInt("max_depth"),
                        result.getLong("total_play_seconds"),
                        result.getLong("longest_play_seconds"),
                        result.getLong("mob_kills"),
                        result.getLong("chests_opened"),
                        result.getLong("treasures_collected"),
                        result.getLong("total_sales"),
                        result.getLong("early_leaves"),
                        result.getLong("disconnects"),
                        utcInstant(result.getObject("first_seen_at", LocalDateTime.class)),
                        utcInstant(result.getObject("last_seen_at", LocalDateTime.class))
                ));
            }
        }
    }

    Optional<PlayerStatistics> playerStatistics(String playerName) throws SQLException {
        UUID playerId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_uuid
                     FROM azirouge_player_statistics
                     WHERE player_name = ?
                     ORDER BY last_seen_at DESC
                     LIMIT 1
                     """)) {
            statement.setString(1, playerName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                playerId = bytesUuid(result.getBytes("player_uuid"));
            }
        }
        return playerStatistics(playerId);
    }

    Map<RankingPeriod, List<RankingEntry>> rankings(ZoneId timezone, int topSize, Instant now) throws SQLException {
        Map<RankingPeriod, List<RankingEntry>> rankings = new EnumMap<>(RankingPeriod.class);
        try (Connection connection = dataSource.getConnection()) {
            for (RankingPeriod period : RankingPeriod.values()) {
                rankings.put(period, ranking(connection, RankingWindows.resolve(period, now, timezone), topSize));
            }
        }
        return Map.copyOf(rankings);
    }

    private List<RankingEntry> ranking(Connection connection, RankingWindow window, int topSize) throws SQLException {
        String filter = window.isTotal() ? "" : " WHERE reached_at >= ? AND reached_at < ?";
        String joinFilter = window.isTotal() ? "" : " AND reached.reached_at >= ? AND reached.reached_at < ?";
        String sql = """
                SELECT best.player_uuid, statistics.player_name, best.max_round,
                       MIN(reached.reached_at) AS reached_at
                FROM (
                    SELECT player_uuid, MAX(round_number) AS max_round
                    FROM azirouge_round_reaches
                """ + filter + """
                    GROUP BY player_uuid
                ) best
                INNER JOIN azirouge_round_reaches reached
                    ON reached.player_uuid = best.player_uuid
                    AND reached.round_number = best.max_round
                """ + joinFilter + """
                INNER JOIN azirouge_player_statistics statistics
                    ON statistics.player_uuid = best.player_uuid
                GROUP BY best.player_uuid, statistics.player_name, best.max_round
                ORDER BY best.max_round DESC, reached_at ASC, best.player_uuid ASC
                LIMIT ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (!window.isTotal()) {
                statement.setObject(index++, utcDateTime(window.startInclusive()));
                statement.setObject(index++, utcDateTime(window.endExclusive()));
                statement.setObject(index++, utcDateTime(window.startInclusive()));
                statement.setObject(index++, utcDateTime(window.endExclusive()));
            }
            statement.setInt(index, topSize);
            List<RankingEntry> entries = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new RankingEntry(
                            bytesUuid(result.getBytes("player_uuid")),
                            result.getString("player_name"),
                            result.getInt("max_round"),
                            utcInstant(result.getObject("reached_at", LocalDateTime.class))
                    ));
                }
            }
            return List.copyOf(entries);
        }
    }

    private void migrate() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_SCHEMA_VERSION);
            statement.executeUpdate(CREATE_PLAYER_STATISTICS);
            statement.executeUpdate(CREATE_RUNS);
            statement.executeUpdate(CREATE_PLAYER_RUNS);
            statement.executeUpdate(CREATE_ROUND_REACHES);
            statement.executeUpdate(CREATE_STAT_EVENTS);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO azirouge_schema_version (singleton_id, version, updated_at)
                     VALUES (1, ?, ?)
                     ON DUPLICATE KEY UPDATE
                         version = GREATEST(version, VALUES(version)),
                         updated_at = VALUES(updated_at)
                     """)) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.setObject(2, utcDateTime(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void ensureRun(Connection connection, UUID runId, Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO azirouge_runs (run_id, created_at)
                VALUES (?, ?)
                """)) {
            statement.setBytes(1, uuidBytes(runId));
            statement.setObject(2, utcDateTime(occurredAt));
            statement.executeUpdate();
        }
    }

    private void upsertPlayer(Connection connection, PlayerSnapshot player, Instant occurredAt) throws SQLException {
        LocalDateTime timestamp = utcDateTime(occurredAt);
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_PLAYER)) {
            statement.setBytes(1, uuidBytes(player.playerId()));
            statement.setString(2, player.playerName());
            statement.setObject(3, timestamp);
            statement.setObject(4, timestamp);
            statement.setObject(5, timestamp);
            statement.executeUpdate();
        }
    }

    private void ensurePlayerRun(Connection connection, UUID runId, UUID playerId, Instant occurredAt) throws SQLException {
        LocalDateTime timestamp = utcDateTime(occurredAt);
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO azirouge_player_runs (
                    run_id, player_uuid, joined_at, active_since
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuidBytes(runId));
            statement.setBytes(2, uuidBytes(playerId));
            statement.setObject(3, timestamp);
            statement.setObject(4, timestamp);
            inserted = statement.executeUpdate();
        }
        if (inserted == 1) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE azirouge_player_statistics
                    SET sessions_joined = sessions_joined + 1,
                        last_seen_at = GREATEST(last_seen_at, ?),
                        updated_at = GREATEST(updated_at, ?)
                    WHERE player_uuid = ?
                    """)) {
                statement.setObject(1, timestamp);
                statement.setObject(2, timestamp);
                statement.setBytes(3, uuidBytes(playerId));
                statement.executeUpdate();
            }
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE azirouge_player_runs
                SET last_exit_reason = CASE WHEN active_since IS NULL THEN NULL ELSE last_exit_reason END,
                    active_since = COALESCE(active_since, ?)
                WHERE run_id = ? AND player_uuid = ?
                """)) {
            statement.setObject(1, timestamp);
            statement.setBytes(2, uuidBytes(runId));
            statement.setBytes(3, uuidBytes(playerId));
            statement.executeUpdate();
        }
    }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant utcInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static UUID bytesUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("Expected a 16-byte UUID.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    enum CounterEvent {
        DEATH("deaths"),
        MOB_KILL("mob_kills"),
        CHEST_OPENED("chests_opened"),
        TREASURE("treasures_collected"),
        SALE("total_sales");

        private final String columnName;

        CounterEvent(String columnName) {
            this.columnName = columnName;
        }

        String columnName() {
            return columnName;
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
