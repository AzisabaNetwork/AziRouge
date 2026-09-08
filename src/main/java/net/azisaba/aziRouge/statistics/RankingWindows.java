package net.azisaba.aziRouge.statistics;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

public final class RankingWindows {
    private RankingWindows() {
    }

    public static RankingWindow resolve(RankingPeriod period, Instant now, ZoneId timezone) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(timezone, "timezone");
        if (period == RankingPeriod.TOTAL) {
            return RankingWindow.total();
        }

        LocalDate today = now.atZone(timezone).toLocalDate();
        LocalDate startDate = switch (period) {
            case DAILY -> today;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1);
            case TOTAL -> throw new IllegalStateException("TOTAL is handled above");
        };
        LocalDate endDate = switch (period) {
            case DAILY -> startDate.plusDays(1);
            case WEEKLY -> startDate.plusWeeks(1);
            case MONTHLY -> startDate.plusMonths(1);
            case TOTAL -> throw new IllegalStateException("TOTAL is handled above");
        };
        ZonedDateTime start = startDate.atStartOfDay(timezone);
        ZonedDateTime end = endDate.atStartOfDay(timezone);
        return new RankingWindow(start.toInstant(), end.toInstant());
    }
}
