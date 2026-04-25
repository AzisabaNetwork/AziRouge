package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.BlockBox;

public record PortalDungeonToHomeSettings(
        BlockBox area,
        float destinationYawOffset
) {
}
