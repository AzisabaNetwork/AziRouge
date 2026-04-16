package net.azisaba.aziRouge.entity;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.MobProfileSettings;

public record MobAiContext(
        AziRouge plugin,
        MobProfile profile,
        MobProfileSettings settings
) {
}
