package net.azisaba.aziRouge.config;

public record AziRougeSettings(
        MobSpawnSettings mobSpawn,
        ChestSettings chest,
        TreasureSettings treasure,
        TrapSettings traps
) {
}
