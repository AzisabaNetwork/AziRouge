package net.azisaba.aziRouge.config;

import org.bukkit.Material;

import java.util.Map;

public record EconomySettings(
        long initialBalance,
        EconomyMaintenanceSettings maintenance,
        Map<Material, Long> sellPrices
) {
}
