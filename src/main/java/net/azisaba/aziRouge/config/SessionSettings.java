package net.azisaba.aziRouge.config;

public record SessionSettings(
        int defaultMaxPlayers,
        int maxMaxPlayers,
        int idleTimeoutSeconds,
        String worldNamePrefix
) {
}
