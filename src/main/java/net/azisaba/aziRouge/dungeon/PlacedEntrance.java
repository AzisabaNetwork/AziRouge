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
        if (!isDirectlyAdjacent(other)) {
            return false;
        }
        if (!isPieceAdjacentOnFacing(other)) {
            return false;
        }
        if (overlapY(other) <= 0 || overlapLateral(other) <= 0) {
            return false;
        }
        return isBottomAlignedOnY(other) && isCenteredOnLateral(other);
    }

    private boolean isDirectlyAdjacent(PlacedEntrance other) {
        Direction.Axis axis = worldFacing.axis();
        int expectedOtherPlane = planeBox.min(axis) + (axis == Direction.Axis.X ? worldFacing.dx() : worldFacing.dz());
        return other.planeBox.min(axis) == expectedOtherPlane && other.planeBox.max(axis) == expectedOtherPlane;
    }

    private boolean isPieceAdjacentOnFacing(PlacedEntrance other) {
        BlockBox own = piece.worldBounds();
        BlockBox target = other.piece.worldBounds();
        return switch (worldFacing) {
            case NORTH -> own.minZ() == target.maxZ() + 1;
            case SOUTH -> own.maxZ() + 1 == target.minZ();
            case EAST -> own.maxX() + 1 == target.minX();
            case WEST -> own.minX() == target.maxX() + 1;
        };
    }

    private int overlapY(PlacedEntrance other) {
        return overlapSize(planeBox.minY(), planeBox.maxY(), other.planeBox.minY(), other.planeBox.maxY());
    }

    private int overlapLateral(PlacedEntrance other) {
        return switch (worldFacing.axis()) {
            case X -> overlapSize(planeBox.minZ(), planeBox.maxZ(), other.planeBox.minZ(), other.planeBox.maxZ());
            case Y -> 0;
            case Z -> overlapSize(planeBox.minX(), planeBox.maxX(), other.planeBox.minX(), other.planeBox.maxX());
        };
    }

    private boolean isBottomAlignedOnY(PlacedEntrance other) {
        return planeBox.minY() == other.planeBox.minY();
    }

    private boolean isCenteredOnLateral(PlacedEntrance other) {
        return switch (worldFacing.axis()) {
            case X -> centerDifference(planeBox.minZ(), planeBox.maxZ(), other.planeBox.minZ(), other.planeBox.maxZ()) <= 1;
            case Y -> false;
            case Z -> centerDifference(planeBox.minX(), planeBox.maxX(), other.planeBox.minX(), other.planeBox.maxX()) <= 1;
        };
    }

    private int centerDifference(int minA, int maxA, int minB, int maxB) {
        return Math.abs((minA + maxA) - (minB + maxB));
    }

    private int overlapSize(int minA, int maxA, int minB, int maxB) {
        int overlapMin = Math.max(minA, minB);
        int overlapMax = Math.min(maxA, maxB);
        return overlapMin > overlapMax ? 0 : overlapMax - overlapMin + 1;
    }
}
