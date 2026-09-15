package net.azisaba.aziRouge.game;

public record QuotaProgress(int consecutiveMisses, int remainingMisses, boolean gameOver) {
    public static QuotaProgress afterRound(int previousMisses, boolean achieved, int maxConsecutiveMisses) {
        int maximum = Math.max(1, maxConsecutiveMisses);
        int misses = achieved ? 0 : Math.min(maximum, Math.max(0, previousMisses) + 1);
        return new QuotaProgress(misses, Math.max(0, maximum - misses), misses >= maximum);
    }
}
