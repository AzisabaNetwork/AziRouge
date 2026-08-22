package net.azisaba.aziRouge.statistics;

import java.time.Instant;
import java.util.UUID;

public record PlayerStatistics(
        UUID playerId,
        String playerName,
        int maxRound,
        long totalRoundsReached,
        long sessionsJoined,
        long deaths,
        long gameOvers,
        int maxDepth,
        long totalPlaySeconds,
        long longestPlaySeconds,
        long mobKills,
        long chestsOpened,
        long treasuresCollected,
        long totalSales,
        long earlyLeaves,
        long disconnects,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
}
