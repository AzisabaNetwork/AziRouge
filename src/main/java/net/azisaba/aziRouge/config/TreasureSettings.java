package net.azisaba.aziRouge.config;

public record TreasureSettings(
        double baseSpawnChance,
        double depthMultiplier,
        double maxSpawnChance,
        int minPerDungeon,
        ChestLootTierSettings tier1,
        ChestLootTierSettings tier2,
        ChestLootTierSettings tier3
) {
    public double spawnChance(int depth) {
        return clamp(baseSpawnChance + Math.max(0, depth) * depthMultiplier, 0.0D, maxSpawnChance);
    }

    public ChestLootTierSettings tierForDepth(int depth) {
        if (depth <= 2) {
            return tier1;
        }
        if (depth <= 5) {
            return tier2;
        }
        return tier3;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
