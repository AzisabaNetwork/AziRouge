package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.math.Rotation;
import net.azisaba.aziRouge.template.PieceTemplate;

import java.util.List;

public record PlacedPiece(
        int index,
        PieceTemplate template,
        Rotation rotation,
        IntVector3 origin,
        int depth,
        BlockBox worldBounds,
        List<PlacedEntrance> entrances
) {
}
