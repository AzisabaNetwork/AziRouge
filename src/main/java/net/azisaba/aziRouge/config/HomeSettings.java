package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;

public record HomeSettings(
        IntVector3 spawn,
        BlockBox area
) {
}
