package net.azisaba.aziRouge.template;

import net.azisaba.aziRouge.math.BlockBox;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

public record PieceTemplate(
        String id,
        Path sourceFile,
        Path schematicPath,
        double weight,
        int minGenerations,
        int maxGenerations,
        int minEntranceConnections,
        BlockBox bounds,
        List<EntranceTemplate> entrances,
        List<String> deniedAdjacentPieces
) {
    public boolean allowsAdjacentPiece(String otherPieceId) {
        if (deniedAdjacentPieces.isEmpty()) {
            return true;
        }
        for (String pattern : deniedAdjacentPieces) {
            if (FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(otherPieceId))) {
                return false;
            }
        }
        return true;
    }
}
