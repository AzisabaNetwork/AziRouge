package net.azisaba.aziRouge.math;

public enum Rotation {
    NONE(0),
    CLOCKWISE_90(90),
    CLOCKWISE_180(180),
    CLOCKWISE_270(270);

    private final int degrees;

    Rotation(int degrees) {
        this.degrees = degrees;
    }

    public int degrees() {
        return degrees;
    }

    public IntVector3 apply(IntVector3 point) {
        return switch (this) {
            case NONE -> point;
            case CLOCKWISE_90 -> new IntVector3(point.z(), point.y(), -point.x());
            case CLOCKWISE_180 -> new IntVector3(-point.x(), point.y(), -point.z());
            case CLOCKWISE_270 -> new IntVector3(-point.z(), point.y(), point.x());
        };
    }

    public Direction rotate(Direction direction) {
        return switch (this) {
            case NONE -> direction;
            case CLOCKWISE_90 -> switch (direction) {
                case NORTH -> Direction.WEST;
                case EAST -> Direction.NORTH;
                case SOUTH -> Direction.EAST;
                case WEST -> Direction.SOUTH;
            };
            case CLOCKWISE_180 -> direction.opposite();
            case CLOCKWISE_270 -> switch (direction) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.NORTH;
            };
        };
    }

    public static Rotation fromFacing(Direction localFacing, Direction worldFacing) {
        for (Rotation rotation : values()) {
            if (rotation.rotate(localFacing) == worldFacing) {
                return rotation;
            }
        }
        throw new IllegalArgumentException("No rotation maps " + localFacing + " to " + worldFacing);
    }
}
