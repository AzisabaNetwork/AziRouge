package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.IntVector3;

import java.util.List;

public record GenerationSettings(
        List<String> templatePatterns,
        String startPieceId,
        String worldName,
        IntVector3 origin,
        long defaultSeed,
        int maxDepth,
        double branchChance,
        double entranceBranchBonus,
        double depthPredictionMultiplier,
        int minPieceCount,
        int maxPieceCount
) {
    public double scaledBranchChance(int availableEntranceCount) {
        if (availableEntranceCount <= 1) {
            return 0.0D;
        }
        double multiplier = 1.0D + Math.max(0, availableEntranceCount - 2) * entranceBranchBonus;
        return clamp(branchChance * multiplier, 0.0D, 0.95D);
    }

    public double expectedActivatedEntranceCount(int availableEntranceCount) {
        if (availableEntranceCount <= 0) {
            return 0.0D;
        }
        if (availableEntranceCount == 1) {
            return 1.0D;
        }
        double expected = 1.0D + (availableEntranceCount - 1) * scaledBranchChance(availableEntranceCount);
        return Math.min(availableEntranceCount, expected);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
