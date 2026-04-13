package net.azisaba.aziRouge.template;

import net.azisaba.aziRouge.math.BlockBox;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public record PieceTemplate(
        String id,
        Path sourceFile,
        Path schematicPath,
        double weight,
        BlockBox bounds,
        List<EntranceTemplate> entrances,
        List<EnemySocketTemplate> enemySockets,
        List<String> deniedAdjacentPieces
) {
    public boolean allowsAdjacentPiece(String otherPieceId) {
        if (deniedAdjacentPieces.isEmpty()) {
            return true;
        }
        for (String pattern : deniedAdjacentPieces) {
            if (matches(pattern, otherPieceId)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(String pattern, String candidate) {
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        return candidate.matches(regex.toString());
    }
}
