package net.azisaba.aziRouge.game;

final class DepartureGuard {
    private DepartureGuard() {}

    static boolean canPrepare(SessionState state, RoundState round) {
        return (state == SessionState.LOBBY || state == SessionState.BETWEEN_ROUNDS)
                && round != RoundState.PREPARING && round != RoundState.ENDING;
    }

    static boolean validDepth(Float depth, int maximum) {
        return depth != null && Float.isFinite(depth) && depth >= 1 && depth <= maximum && depth == Math.floor(depth);
    }
}
