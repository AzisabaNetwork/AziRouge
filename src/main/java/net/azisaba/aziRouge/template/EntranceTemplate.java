package net.azisaba.aziRouge.template;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;

public record EntranceTemplate(String id, Direction facing, IntVector3 point1, IntVector3 point2) {
    public BlockBox planeBox() {
        return BlockBox.fromPoints(point1, point2);
    }

    public int width() {
        return switch (facing.axis()) {
            case X -> planeBox().sizeZ();
            case Y -> throw new IllegalStateException("Vertical entrances are not supported");
            case Z -> planeBox().sizeX();
        };
    }

    public int height() {
        return planeBox().sizeY();
    }

    public void validate(String pieceId) {
        BlockBox plane = planeBox();
        switch (facing) {
            case NORTH, SOUTH -> {
                if (plane.sizeZ() != 1) {
                    throw new IllegalArgumentException("Piece " + pieceId + " entrance " + id + " must have constant z");
                }
            }
            case EAST, WEST -> {
                if (plane.sizeX() != 1) {
                    throw new IllegalArgumentException("Piece " + pieceId + " entrance " + id + " must have constant x");
                }
            }
        }
    }
}
