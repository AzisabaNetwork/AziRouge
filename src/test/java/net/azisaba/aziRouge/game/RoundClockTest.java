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
}
