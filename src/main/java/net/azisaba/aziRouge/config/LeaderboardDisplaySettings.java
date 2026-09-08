package net.azisaba.aziRouge.config;

public record LeaderboardDisplaySettings(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String title
) {
}
