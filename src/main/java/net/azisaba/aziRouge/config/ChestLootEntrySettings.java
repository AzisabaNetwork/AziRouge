package net.azisaba.aziRouge.config;

import java.util.Map;

public record ChestLootEntrySettings(
        String material,
        int weight,
        int minAmount,
        int maxAmount,
        String potionType,
        Map<String, Integer> storedEnchantments
) {
}
