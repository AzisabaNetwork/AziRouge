package net.azisaba.aziRouge.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundClockTest {
    @Test
    void convertsMinecraftTicksToClockTime() {
        assertEquals("06:00", RoundClock.format(0));
        assertEquals("12:00", RoundClock.format(6_000));
        assertEquals("18:00", RoundClock.format(12_000));
        assertEquals("00:00", RoundClock.format(18_000));
        assertEquals("06:00", RoundClock.format(24_000));
    }

    @Test
    void choosesTheNearestDeadlineWarning() {
        assertEquals(0, RoundClock.deadlineWarningHours(3_001));
        assertEquals(3, RoundClock.deadlineWarningHours(3_000));
        assertEquals(1, RoundClock.deadlineWarningHours(1_000));
        assertEquals(0, RoundClock.deadlineWarningHours(0));
    }
}
