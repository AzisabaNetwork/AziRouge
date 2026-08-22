package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.statistics.RankingPeriod;

import java.time.ZoneId;
import java.util.Map;

public record LeaderboardSettings(
        boolean enabled,
        ZoneId timezone,
        int topSize,
        long updateIntervalSeconds,
        Map<RankingPeriod, LeaderboardDisplaySettings> displays
) {
    public LeaderboardSettings {
        displays = Map.copyOf(displays);
    }

    public LeaderboardDisplaySettings display(RankingPeriod period) {
        return displays.get(period);
    }
}
