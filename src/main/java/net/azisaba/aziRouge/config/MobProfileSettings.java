package net.azisaba.aziRouge.config;

import java.util.List;

public record MobProfileSettings(
        int weight,
        int power,
        double maxHealth,
        double movementSpeed,
        double attackDamage,
        MobAiSettings ai,
        List<MobDropEntrySettings> drops
) {
}
