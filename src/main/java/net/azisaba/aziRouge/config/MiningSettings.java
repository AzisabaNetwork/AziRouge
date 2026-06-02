package net.azisaba.aziRouge.config;

import org.bukkit.Material;

import java.util.List;

public record MiningSettings(
        boolean enabled,
        int oresPerPiece,
        int triggersPerDungeon,
        Material backingMaterial,
        Material triggerMaterial,
        int minTriggerGimmickDistance,
        int chainStepTicks,
        List<MiningOreSettings> ores,
        List<MiningGimmickSettings> gimmicks
) {
}
