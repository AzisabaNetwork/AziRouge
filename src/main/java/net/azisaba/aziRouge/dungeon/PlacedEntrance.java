package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.template.EntranceTemplate;

public record PlacedEntrance(
        PlacedPiece piece,
        EntranceTemplate template,
        Direction worldFacing,
        BlockBox planeBox,
        BlockBox openingBox
) {
    public String key() {
        return piece.index() + ":" + template.id();
    }

    public int width() {
        return template.width();
    }

    public int height() {
        return template.height();
    }

    public boolean isDoorCompatible() {
        return width() == 1 && height() == 2;
    }

    public boolean canConnectTo(PlacedEntrance other) {
        if (worldFacing != other.worldFacing.opposite()) {
            return false;
        }

        Direction.Axis axis = worldFacing.axis();
        int expectedOtherPlane = planeBox.min(axis) + (axis == Direction.Axis.X ? worldFacing.dx() : worldFacing.dz());
        if (other.planeBox.min(axis) != expectedOtherPlane || other.planeBox.max(axis) != expectedOtherPlane) {
            return false;
        }

        return switch (axis) {
            case X -> fullyAdjacent(planeBox.minZ(), planeBox.maxZ(), other.planeBox.minZ(), other.planeBox.maxZ())
                    && fullyAdjacent(planeBox.minY(), planeBox.maxY(), other.planeBox.minY(), other.planeBox.maxY());
            case Y -> false;
            case Z -> fullyAdjacent(planeBox.minX(), planeBox.maxX(), other.planeBox.minX(), other.planeBox.maxX())
                    && fullyAdjacent(planeBox.minY(), planeBox.maxY(), other.planeBox.minY(), other.planeBox.maxY());
        };
    }

    private boolean fullyAdjacent(int minA, int maxA, int minB, int maxB) {
        int overlapMin = Math.max(minA, minB);
        int overlapMax = Math.min(maxA, maxB);
        if (overlapMin > overlapMax) {
            return false;
        }
        int overlapSize = overlapMax - overlapMin + 1;
        return overlapSize == Math.min(maxA - minA + 1, maxB - minB + 1);
    }
}
