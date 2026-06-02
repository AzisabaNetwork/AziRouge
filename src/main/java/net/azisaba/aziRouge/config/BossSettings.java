package net.azisaba.aziRouge.config;

import java.util.List;

public record BossSettings(
        List<BossBattleSettings> battles,
        double reviveRadius,
        int reviveHoldTicks,
        int portalCooldownSeconds
) {
}
