package net.azisaba.aziRouge.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DepartureGuardTest {
    @Test
    void onlyIdleLobbyMayBePreparedManually() {
        assertTrue(DepartureGuard.canPrepare(SessionState.LOBBY, RoundState.ENDED));
        for (SessionState state : SessionState.values()) {
            assertFalse(DepartureGuard.canPrepare(state, RoundState.PREPARING));
            assertFalse(DepartureGuard.canPrepare(state, RoundState.ENDING));
        }
        for (SessionState state : new SessionState[]{SessionState.IN_ROUND, SessionState.GAME_OVER, SessionState.CLOSING}) {
            for (RoundState round : RoundState.values()) assertFalse(DepartureGuard.canPrepare(state, round));
        }
    }

    @Test
    void anEndedRoundMustReturnToTheLobbyForDepthSelection() {
        assertTrue(DepartureGuard.canStartRound(SessionState.LOBBY, RoundState.ENDED));
        assertFalse(DepartureGuard.canStartRound(SessionState.IN_ROUND, RoundState.ENDED));
        assertFalse(DepartureGuard.canStartRound(SessionState.IN_ROUND, RoundState.ACTIVE));
    }

    @Test
    void onlyDayZeroAdvancesWhenStartingADay() {
        assertEquals(1, DepartureGuard.dayToStart(0));
        assertEquals(2, DepartureGuard.dayToStart(2));
    }

    @Test
    void dialogDepthRejectsMissingNonFiniteFractionalAndOutOfRangeInput() {
        for (Float depth : new Float[]{null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0F, -1F, 1.5F, 13F}) {
            assertFalse(DepartureGuard.validDepth(depth, 12));
        }
        assertTrue(DepartureGuard.validDepth(1F, 12));
        assertTrue(DepartureGuard.validDepth(12F, 12));
    }
}
