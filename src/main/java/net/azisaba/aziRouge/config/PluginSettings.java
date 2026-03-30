package net.azisaba.aziRouge.config;

public record PluginSettings(
        GenerationSettings generation,
        DoorSettings door,
        DebugSettings debug,
        EnemySettings enemies
) {
}
