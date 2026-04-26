package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.IntVector3;

public record GuiSettings(
        String worldName,
        IntVector3 position,
        float yaw,
        String text,
        int maxDepth,
        int defaultDepth
) {
}
