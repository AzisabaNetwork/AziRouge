package net.azisaba.aziRouge.template;

import net.azisaba.aziRouge.math.BlockBox;

import java.nio.file.Path;
import java.util.List;

public record PieceTemplate(
        String id,
        Path sourceFile,
        Path schematicPath,
        double weight,
        BlockBox bounds,
        List<EntranceTemplate> entrances,
        List<EnemySocketTemplate> enemySockets
) {
}
