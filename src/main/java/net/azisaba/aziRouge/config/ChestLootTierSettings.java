package net.azisaba.aziRouge.config;

import java.util.List;

public record ChestLootTierSettings(
        int minRolls,
        int maxRolls,
        List<ChestLootEntrySettings> entries
) {
}
