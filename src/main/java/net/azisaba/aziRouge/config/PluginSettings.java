package net.azisaba.aziRouge.config;

public record PluginSettings(
        GenerationSettings generation,
        DoorSettings door,
        DebugSettings debug,
        DatabaseSettings database,
        LeaderboardSettings leaderboard,
        SessionSettings sessions,
        HomeSettings home,
        DungeonSettings dungeon,
        PortalSettings portals,
        EconomySettings economy,
        ShopSettings shop,
        GuiSettings gui,
        PlayerSettings player,
        EnemySettings enemies,
        AziRougeSettings azirouge,
        JoinSettings joinSettings
) {
}
