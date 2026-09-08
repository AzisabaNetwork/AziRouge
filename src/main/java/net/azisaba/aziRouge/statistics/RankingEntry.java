package net.azisaba.aziRouge.statistics;

import java.time.Instant;
import java.util.UUID;

public record RankingEntry(
        UUID playerId,
        String playerName,
        int maxRound,
        Instant reachedAt
) {
}
