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
        return Math.clamp(baseSpawnChance + Math.max(0, depth) * depthMultiplier, 0.0D, maxSpawnChance);
    }

    public ChestLootTierSettings tierForDepth(int depth) {
        return ChestLootTierSettings.forDepth(depth, tier1, tier2, tier3);
    }
}
