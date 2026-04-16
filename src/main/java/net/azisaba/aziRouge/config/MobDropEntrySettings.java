package net.azisaba.aziRouge.config;

import java.util.Map;

public record MobDropEntrySettings(
        String material,
        double chance,
        double depthChanceMultiplier,
        int minAmount,
        int maxAmount,
        int minDepth,
        int maxDepth,
        String potionType,
        Map<String, Integer> storedEnchantments
) {
    public boolean matchesDepth(int depth) {
        return depth >= minDepth && depth <= maxDepth;
    }

    public double chanceAtDepth(int depth) {
        double resolved = chance + Math.max(0, depth) * depthChanceMultiplier;
        return Math.max(0.0D, Math.min(1.0D, resolved));
    }
}
