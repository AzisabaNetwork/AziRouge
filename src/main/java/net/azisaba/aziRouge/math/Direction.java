package net.azisaba.aziRouge.math;

public enum Direction {
    NORTH(0, 0, -1, Axis.Z),
    EAST(1, 0, 0, Axis.X),
    SOUTH(0, 0, 1, Axis.Z),
    WEST(-1, 0, 0, Axis.X);

    private final int dx;
    private final int dy;
    private final int dz;
    private final Axis axis;

    Direction(int dx, int dy, int dz, Axis axis) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.axis = axis;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    public int dz() {
        return dz;
    }

    public Axis axis() {
        return axis;
    }

    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case EAST -> WEST;
            case SOUTH -> NORTH;
            case WEST -> EAST;
        };
    }

    public enum Axis {
        X, Y, Z
    }
}
