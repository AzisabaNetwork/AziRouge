package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.IntVector3;

import java.util.List;
import java.util.Random;

public record GenerationSettings(
        List<String> templatePatterns,
        String startPieceId,
        String worldName,
        IntVector3 origin,
        long defaultSeed,
        int maxDepth,
        double branchChance,
        int targetPieceCount,
        int pieceCountJitter,
        int minPieceCount,
        int maxPieceCount
) {
    public int resolveTargetPieceCount(Random random) {
        int jitter = pieceCountJitter <= 0 ? 0 : random.nextInt(pieceCountJitter * 2 + 1) - pieceCountJitter;
        int resolved = targetPieceCount + jitter;
        resolved = Math.max(minPieceCount, resolved);
        resolved = Math.min(maxPieceCount, resolved);
        return Math.max(1, resolved);
    }
}
