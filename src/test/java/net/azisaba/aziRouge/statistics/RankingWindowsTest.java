package net.azisaba.aziRouge.statistics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingWindowsTest {
    @Test
    void dailyWindowUsesConfiguredTimezone() {
        RankingWindow window = RankingWindows.resolve(
                RankingPeriod.DAILY,
                Instant.parse("2026-08-22T12:34:56Z"),
                ZoneId.of("Asia/Tokyo")
        );

        assertEquals(Instant.parse("2026-08-21T15:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-08-22T15:00:00Z"), window.endExclusive());
    }

    @Test
    void weeklyWindowStartsOnMonday() {
        RankingWindow window = RankingWindows.resolve(
                RankingPeriod.WEEKLY,
                Instant.parse("2026-08-22T12:34:56Z"),
                ZoneId.of("UTC")
        );

        assertEquals(Instant.parse("2026-08-17T00:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-08-24T00:00:00Z"), window.endExclusive());
    }

    @Test
    void monthlyWindowUsesCalendarMonth() {
        RankingWindow window = RankingWindows.resolve(
                RankingPeriod.MONTHLY,
                Instant.parse("2024-02-29T23:59:59Z"),
                ZoneId.of("UTC")
        );

        assertEquals(Instant.parse("2024-02-01T00:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2024-03-01T00:00:00Z"), window.endExclusive());
    }

    @Test
    void dailyWindowHonorsDaylightSavingBoundary() {
        RankingWindow window = RankingWindows.resolve(
                RankingPeriod.DAILY,
                Instant.parse("2026-03-08T16:00:00Z"),
                ZoneId.of("America/New_York")
        );

        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), window.endExclusive());
        assertEquals(Duration.ofHours(23), Duration.between(window.startInclusive(), window.endExclusive()));
    }

    @Test
    void totalWindowIsUnbounded() {
        RankingWindow window = RankingWindows.resolve(
                RankingPeriod.TOTAL,
                Instant.parse("2026-08-22T12:34:56Z"),
                ZoneId.of("UTC")
        );

        assertTrue(window.isTotal());
        assertNull(window.startInclusive());
        assertNull(window.endExclusive());
    }
}
