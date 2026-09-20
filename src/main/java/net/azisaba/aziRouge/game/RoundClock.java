package net.azisaba.aziRouge.game;

import java.util.Locale;

final class RoundClock {
    private static final long DAY_TICKS = 24_000L;
    private static final long DAY_MINUTES = 24L * 60L;

    private RoundClock() {
    }

    static String format(long ticks) {
        long minutes = (Math.floorMod(ticks, DAY_TICKS) * DAY_MINUTES / DAY_TICKS + 6L * 60L) % DAY_MINUTES;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60L, minutes % 60L);
    }

    static int deadlineWarningHours(long remainingTicks) {
        if (remainingTicks <= 0L) {
            return 0;
        }
        if (remainingTicks <= 1_000L) {
            return 1;
        }
        return remainingTicks <= 3_000L ? 3 : 0;
    }
}
