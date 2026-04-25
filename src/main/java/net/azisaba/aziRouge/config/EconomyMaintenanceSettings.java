package net.azisaba.aziRouge.config;

public record EconomyMaintenanceSettings(
        long base,
        long perRound,
        double multiplier
) {
}
