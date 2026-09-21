package net.azisaba.aziRouge.game;

final class DepartureGuard {
    private DepartureGuard() {}

    static boolean canPrepare(SessionState state, RoundState round) {
        return state == SessionState.LOBBY && round == RoundState.ENDED;
    }

    static boolean canStartRound(SessionState state, RoundState round) {
        return canPrepare(state, round);
    }

    static int dayToStart(int currentDay) {
        return Math.max(1, currentDay);
    }

    static boolean validDepth(Float depth, int maximum) {
        return depth != null && Float.isFinite(depth) && depth >= 1 && depth <= maximum && depth == Math.floor(depth);
    }
}
