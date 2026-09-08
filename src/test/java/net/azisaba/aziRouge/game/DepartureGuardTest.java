package net.azisaba.aziRouge.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DepartureGuardTest {
    @Test
    void onlyIdleHomePhasesMayStartAnotherRound() {
        assertTrue(DepartureGuard.canPrepare(SessionState.LOBBY, RoundState.ENDED));
        assertTrue(DepartureGuard.canPrepare(SessionState.BETWEEN_ROUNDS, RoundState.ENDED));
        for (SessionState state : SessionState.values()) {
            assertFalse(DepartureGuard.canPrepare(state, RoundState.PREPARING));
            assertFalse(DepartureGuard.canPrepare(state, RoundState.ENDING));
        }
        for (SessionState state : new SessionState[]{SessionState.IN_ROUND, SessionState.GAME_OVER, SessionState.CLOSING}) {
            for (RoundState round : RoundState.values()) assertFalse(DepartureGuard.canPrepare(state, round));
        }
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
