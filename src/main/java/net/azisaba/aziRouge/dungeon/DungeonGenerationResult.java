package net.azisaba.aziRouge.dungeon;

import java.util.List;

public record DungeonGenerationResult(
        long seed,
        int targetPieceCount,
        int placedPieceCount,
        int connectionCount,
        List<EnemySpawnReservation> enemyReservations
) {
}
