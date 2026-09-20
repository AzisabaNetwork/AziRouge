package net.azisaba.aziRouge.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionScoreboardServiceTest {
    @Test
    void angerGaugeGrowsAndChangesFromGreenToOrangeToRed() {
        assertEquals("§a§7" + "|".repeat(19), AngerGauge.render(0, 3));
        assertEquals("§a" + "|".repeat(7) + "§7" + "|".repeat(12), AngerGauge.render(1, 3));
        assertEquals("§6" + "|".repeat(13) + "§7" + "|".repeat(6), AngerGauge.render(2, 3));
        assertEquals("§c" + "|".repeat(19) + "§7", AngerGauge.render(3, 3));
    }
}
