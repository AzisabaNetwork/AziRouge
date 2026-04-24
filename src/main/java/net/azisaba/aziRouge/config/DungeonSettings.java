package net.azisaba.aziRouge.config;

import java.util.Map;

public record DungeonSettings(
        int baseDistanceFromHome,
        int roundSpacing,
        String defaultDifficulty,
        Map<String, DungeonDifficultySettings> difficulties
) {
    public DungeonDifficultySettings difficulty(String id) {
        return difficulties.get(id);
    }
}
