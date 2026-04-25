package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.IntVector3;

import java.util.List;

public record GuiSettings(
        String worldName,
        IntVector3 position,
        float yaw,
        String text,
        List<Integer> depthOptions
) {
}
