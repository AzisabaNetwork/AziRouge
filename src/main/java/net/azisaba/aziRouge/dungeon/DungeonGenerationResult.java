package net.azisaba.aziRouge.dungeon;

import org.bukkit.Location;

import java.util.List;

public record DungeonGenerationResult(
        long seed,
        int targetPieceCount,
        int placedPieceCount,
        int connectionCount,
        List<EnemySpawnReservation> enemyReservations,
        List<PlacedPiece> placedPieces,
        Location spawnLocation
) {
}
