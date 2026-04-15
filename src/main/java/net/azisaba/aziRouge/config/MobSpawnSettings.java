package net.azisaba.aziRouge.config;

public record MobSpawnSettings(
        long intervalSeconds,
        int countPerInterval
) {
    public long intervalTicks() {
        return intervalSeconds * 20L;
    }
}
