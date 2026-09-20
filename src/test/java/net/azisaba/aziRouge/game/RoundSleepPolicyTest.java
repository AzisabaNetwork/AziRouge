package net.azisaba.aziRouge.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundSleepPolicyTest {
    @Test
    void oneOfTwoPlayersMeetsTheDefaultHalfThresholdButIsNotEveryone() {
        assertTrue(RoundSleepPolicy.minimumSleeping(2, 1, 50));
        assertFalse(RoundSleepPolicy.allSleeping(2, 1));
    }

    @Test
    void percentageComparisonRoundsTheRequiredPlayerCountUp() {
        assertFalse(RoundSleepPolicy.minimumSleeping(3, 1, 50));
        assertTrue(RoundSleepPolicy.minimumSleeping(3, 2, 50));
    }

    @Test
    void everyLivingPlayerCanSkipImmediately() {
        assertTrue(RoundSleepPolicy.allSleeping(3, 3));
        assertFalse(RoundSleepPolicy.allSleeping(0, 0));
    }

    @Test
    void requiredPlayerCountIsRoundedUp() {
        assertEquals(2, RoundSleepPolicy.requiredSleeping(3, 50));
        assertEquals(0, RoundSleepPolicy.requiredSleeping(0, 50));
    }
}
