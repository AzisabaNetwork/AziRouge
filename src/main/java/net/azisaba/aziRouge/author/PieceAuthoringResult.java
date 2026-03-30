package net.azisaba.aziRouge.author;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;

import java.nio.file.Path;

public record PieceAuthoringResult(Path templateFile, IntVector3 origin, BlockBox bounds) {
}
