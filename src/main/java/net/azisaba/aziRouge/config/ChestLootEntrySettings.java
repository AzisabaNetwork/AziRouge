package net.azisaba.aziRouge.config;

import java.util.Map;

public record ChestLootEntrySettings(
        String material,
        int weight,
        int minAmount,
        int maxAmount,
        String potionType,
        Map<String, Integer> storedEnchantments,
        java.util.Set<org.bukkit.Material> canDestroy,
        Integer durability
) {
    public ChestLootEntrySettings(
            String material,
            int weight,
            int minAmount,
            int maxAmount,
            String potionType,
            Map<String, Integer> storedEnchantments
    ) {
        this(material, weight, minAmount, maxAmount, potionType, storedEnchantments, java.util.Set.of(), null);
    }
}
