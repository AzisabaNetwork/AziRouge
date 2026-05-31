package net.azisaba.aziRouge.config;

public record PluginSettings(
        GenerationSettings generation,
        DoorSettings door,
        DebugSettings debug,
        SessionSettings sessions,
        HomeSettings home,
        DungeonSettings dungeon,
        PortalSettings portals,
        EconomySettings economy,
        ShopSettings shop,
        GuiSettings gui,
        PlayerSettings player,
        BossSettings boss,
        EnemySettings enemies,
        AziRougeSettings azirouge,
        JoinSettings joinSettings
) {
}
