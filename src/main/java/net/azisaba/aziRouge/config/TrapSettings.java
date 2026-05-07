package net.azisaba.aziRouge.config;

import java.util.List;
import java.util.Random;

public record TrapSettings(
        double baseSpawnChance,
        double depthMultiplier,
        double maxSpawnChance,
        int minPerDungeon,
        List<TrapDefinitionSettings> definitions
) {
    public double spawnChance(int depth) {
        return clamp(baseSpawnChance + Math.max(0, depth) * depthMultiplier, 0.0D, maxSpawnChance);
    }

    public TrapDefinitionSettings selectRandomDefinition(Random random, int depth) {
        int totalWeight = 0;
        for (TrapDefinitionSettings definition : definitions) {
            if (definition.weight() <= 0 || !definition.matchesDepth(depth)) {
                continue;
            }
            totalWeight += definition.weight();
        }
        if (totalWeight <= 0) {
            return null;
        }

        int cursor = random.nextInt(totalWeight);
        for (TrapDefinitionSettings definition : definitions) {
            if (definition.weight() <= 0 || !definition.matchesDepth(depth)) {
                continue;
            }
            cursor -= definition.weight();
            if (cursor < 0) {
                return definition;
            }
        }
        return null;
    }

    public TrapDefinitionSettings definition(String key) {
        for (TrapDefinitionSettings definition : definitions) {
            if (definition.key().equalsIgnoreCase(key)) {
                return definition;
            }
        }
        return null;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
