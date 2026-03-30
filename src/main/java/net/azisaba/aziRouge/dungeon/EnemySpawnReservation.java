package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.math.IntVector3;

public record EnemySpawnReservation(String pieceId, String socketId, String tag, IntVector3 worldPosition) {
}
