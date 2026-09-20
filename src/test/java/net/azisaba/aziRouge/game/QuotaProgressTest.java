package net.azisaba.aziRouge.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaProgressTest {
    @Test
    void missesOnlyEndTheGameAtTheConfiguredConsecutiveLimit() {
        QuotaProgress first = QuotaProgress.afterRound(0, false, 3);
        QuotaProgress second = QuotaProgress.afterRound(first.consecutiveMisses(), false, 3);
        QuotaProgress third = QuotaProgress.afterRound(second.consecutiveMisses(), false, 3);

        assertEquals(2, first.remainingMisses());
        assertFalse(first.gameOver());
        assertEquals(1, second.remainingMisses());
        assertFalse(second.gameOver());
        assertEquals(0, third.remainingMisses());
        assertTrue(third.gameOver());
    }

    @Test
    void reachingTheQuotaReducesAngerByOneStep() {
        QuotaProgress progress = QuotaProgress.afterRound(2, true, 3);

        assertEquals(1, progress.consecutiveMisses());
        assertEquals(2, progress.remainingMisses());
        assertFalse(progress.gameOver());
    }

    @Test
    void reachingTheQuotaCannotReduceAngerBelowZero() {
        QuotaProgress progress = QuotaProgress.afterRound(0, true, 3);

        assertEquals(0, progress.consecutiveMisses());
        assertEquals(3, progress.remainingMisses());
    }
}
