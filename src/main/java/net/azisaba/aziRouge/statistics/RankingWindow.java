package net.azisaba.aziRouge.statistics;

import java.time.Instant;

public record RankingWindow(Instant startInclusive, Instant endExclusive) {
    public RankingWindow {
        if (startInclusive == null && endExclusive != null) {
            throw new IllegalArgumentException("An unbounded start must also have an unbounded end.");
        }
        if (startInclusive != null && (endExclusive == null || !startInclusive.isBefore(endExclusive))) {
            throw new IllegalArgumentException("Ranking window end must be after its start.");
        }
    }

    public static RankingWindow total() {
        return new RankingWindow(null, null);
    }

    public boolean isTotal() {
        return startInclusive == null;
    }
}
