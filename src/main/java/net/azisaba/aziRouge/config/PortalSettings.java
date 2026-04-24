package net.azisaba.aziRouge.config;

public record PortalSettings(
        PortalHomeToDungeonSettings homeToDungeon,
        PortalDungeonToHomeSettings dungeonToHome,
        int cooldownSeconds
) {
}
