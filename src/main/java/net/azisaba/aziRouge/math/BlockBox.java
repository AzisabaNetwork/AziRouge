package net.azisaba.aziRouge.math;

import java.util.ArrayList;
import java.util.List;

public record BlockBox(IntVector3 min, IntVector3 max) {
    public BlockBox {
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Invalid box: " + min + " -> " + max);
        }
    }

    public static BlockBox fromPoints(IntVector3 first, IntVector3 second) {
        return new BlockBox(
                new IntVector3(
                        Math.min(first.x(), second.x()),
                        Math.min(first.y(), second.y()),
                        Math.min(first.z(), second.z())
                ),
                new IntVector3(
                        Math.max(first.x(), second.x()),
                        Math.max(first.y(), second.y()),
                        Math.max(first.z(), second.z())
                )
        );
    }

    public int minX() {
        return min.x();
    }

    public int minY() {
        return min.y();
    }

    public int minZ() {
        return min.z();
    }

    public int maxX() {
        return max.x();
    }

    public int maxY() {
        return max.y();
    }

    public int maxZ() {
        return max.z();
    }

    public int sizeX() {
        return maxX() - minX() + 1;
    }

    public int sizeY() {
        return maxY() - minY() + 1;
    }

    public int sizeZ() {
        return maxZ() - minZ() + 1;
    }

    public BlockBox offset(IntVector3 delta) {
        return new BlockBox(min.add(delta), max.add(delta));
    }

    public BlockBox extend(Direction direction, int distance) {
        if (distance < 0) {
            throw new IllegalArgumentException("distance must be >= 0");
        }
        IntVector3 newMin = min;
        IntVector3 newMax = max;
        switch (direction) {
            case NORTH, WEST -> newMin = newMin.add(direction, distance);
            case EAST, SOUTH -> newMax = newMax.add(direction, distance);
        }
        return BlockBox.fromPoints(newMin, newMax);
    }

    public BlockBox rotate(Rotation rotation) {
        List<IntVector3> corners = corners();
        IntVector3 first = rotation.apply(corners.get(0));
        int minX = first.x();
        int minY = first.y();
        int minZ = first.z();
        int maxX = first.x();
        int maxY = first.y();
        int maxZ = first.z();
        for (int index = 1; index < corners.size(); index++) {
            IntVector3 rotated = rotation.apply(corners.get(index));
            minX = Math.min(minX, rotated.x());
            minY = Math.min(minY, rotated.y());
            minZ = Math.min(minZ, rotated.z());
            maxX = Math.max(maxX, rotated.x());
            maxY = Math.max(maxY, rotated.y());
            maxZ = Math.max(maxZ, rotated.z());
        }
        return new BlockBox(new IntVector3(minX, minY, minZ), new IntVector3(maxX, maxY, maxZ));
    }

    public boolean intersects(BlockBox other) {
        return minX() <= other.maxX() && maxX() >= other.minX()
                && minY() <= other.maxY() && maxY() >= other.minY()
                && minZ() <= other.maxZ() && maxZ() >= other.minZ();
    }

    public boolean contains(IntVector3 point) {
        return contains(point.x(), point.y(), point.z());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX()
                && y >= minY() && y <= maxY()
                && z >= minZ() && z <= maxZ();
    }

    public int min(Direction.Axis axis) {
        return switch (axis) {
            case X -> minX();
            case Y -> minY();
            case Z -> minZ();
        };
    }

    public int max(Direction.Axis axis) {
        return switch (axis) {
            case X -> maxX();
            case Y -> maxY();
            case Z -> maxZ();
        };
    }

    public int size(Direction.Axis axis) {
        return switch (axis) {
            case X -> sizeX();
            case Y -> sizeY();
            case Z -> sizeZ();
        };
    }

    private List<IntVector3> corners() {
        List<IntVector3> corners = new ArrayList<>(8);
        int[] xs = {minX(), maxX()};
        int[] ys = {minY(), maxY()};
        int[] zs = {minZ(), maxZ()};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    corners.add(new IntVector3(x, y, z));
                }
            }
        }
        return corners;
    }
}
