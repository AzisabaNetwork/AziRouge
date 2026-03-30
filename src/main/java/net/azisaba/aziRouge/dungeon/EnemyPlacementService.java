package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.config.EnemySettings;
import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.template.EnemySocketTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EnemyPlacementService {
    private final DebugLogger debugLogger;

    public EnemyPlacementService(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public List<EnemySpawnReservation> plan(List<PlacedPiece> pieces, EnemySettings settings) {
        List<EnemySpawnReservation> reservations = new ArrayList<>();
        for (PlacedPiece piece : pieces) {
            for (EnemySocketTemplate socket : piece.template().enemySockets()) {
                IntVector3 worldPosition = piece.origin().add(piece.rotation().apply(socket.position()));
                reservations.add(new EnemySpawnReservation(piece.template().id(), socket.id(), socket.tag(), worldPosition));
            }
        }
        debugLogger.log("enemy", "plan_complete", Map.of(
                "enabled", settings.enabled(),
                "mode", settings.mode(),
                "reserved", reservations.size()
        ));
        return List.copyOf(reservations);
    }
}
