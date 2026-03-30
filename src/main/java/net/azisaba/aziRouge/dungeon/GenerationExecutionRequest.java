package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.World;

import java.util.List;

public record GenerationExecutionRequest(
        List<String> templatePatterns,
        String startPieceId,
        World world,
        IntVector3 origin,
        long seed
) {
}
