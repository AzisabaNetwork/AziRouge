package net.azisaba.aziRouge.math;

public record IntVector3(int x, int y, int z) {
    public IntVector3 add(IntVector3 other) {
        return new IntVector3(x + other.x, y + other.y, z + other.z);
    }

    public IntVector3 subtract(IntVector3 other) {
        return new IntVector3(x - other.x, y - other.y, z - other.z);
    }

    public IntVector3 add(Direction direction, int amount) {
        return new IntVector3(
                x + direction.dx() * amount,
                y + direction.dy() * amount,
                z + direction.dz() * amount
        );
    }
}
