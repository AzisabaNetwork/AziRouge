package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.Material;

public record BossBattleSettings(
        String id,
        String villagerTag,
        int minRound,
        IntVector3 destination,
        float yaw,
        BlockBox returnPortalArea,
        Material returnPortalMaterial
) {
}
