package net.azisaba.aziRouge.config;

import java.util.List;

public record ChestLootTierSettings(
        int minRolls,
        int maxRolls,
        List<ChestLootEntrySettings> entries
) {
    public static ChestLootTierSettings forDepth(
            int depth,
            ChestLootTierSettings tier1,
            ChestLootTierSettings tier2,
            ChestLootTierSettings tier3
    ) {
        if (depth <= 2) {
            return tier1;
        }
        if (depth <= 5) {
            return tier2;
        }
        return tier3;
    }
}
