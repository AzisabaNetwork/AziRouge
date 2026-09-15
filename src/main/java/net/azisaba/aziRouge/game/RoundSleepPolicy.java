package net.azisaba.aziRouge.game;

final class RoundSleepPolicy {
    private RoundSleepPolicy() {
    }

    static boolean allSleeping(int alivePlayers, int sleepingPlayers) {
        return alivePlayers > 0 && sleepingPlayers >= alivePlayers;
    }

    static boolean minimumSleeping(int alivePlayers, int sleepingPlayers, int minimumPercentage) {
        return alivePlayers > 0
                && sleepingPlayers > 0
                && (long) sleepingPlayers * 100L >= (long) alivePlayers * Math.clamp(minimumPercentage, 1, 100);
    }
}
