package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;

public record PortalHomeToDungeonSettings(
        BlockBox area,
        IntVector3 destinationOffset
) {
}
