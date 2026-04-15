package net.azisaba.aziRouge.config;

public record ChestSettings(
        double baseSpawnChance,
        double depthMultiplier,
        double maxSpawnChance
) {
    public double spawnChance(int depth) {
        return clamp(baseSpawnChance + Math.max(0, depth) * depthMultiplier, 0.0D, maxSpawnChance);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
