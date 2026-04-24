package net.azisaba.aziRouge.config;

public record PluginSettings(
        GenerationSettings generation,
        DoorSettings door,
        DebugSettings debug,
        SessionSettings sessions,
        HomeSettings home,
        EnemySettings enemies,
        AziRougeSettings azirouge
) {
}
