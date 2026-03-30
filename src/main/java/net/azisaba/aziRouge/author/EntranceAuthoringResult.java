package net.azisaba.aziRouge.author;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;

import java.nio.file.Path;

public record EntranceAuthoringResult(Path templateFile, IntVector3 origin, BlockBox plane, Direction facing) {
}
