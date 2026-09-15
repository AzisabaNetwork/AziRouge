package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.IntVector3;

public record EconomyQuotaSettings(
        long base,
        long perRound,
        double multiplier,
        int maxConsecutiveMisses,
        int warningRemaining,
        IntVector3 deliveryChest
) {
}
