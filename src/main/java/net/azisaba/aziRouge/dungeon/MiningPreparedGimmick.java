package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.config.MiningGimmickType;
import net.azisaba.aziRouge.math.BlockBox;
import org.bukkit.Location;

import java.util.List;

public record MiningPreparedGimmick(
        MiningGimmickType type,
        Location target,
        PlacedPiece targetPiece,
        List<BlockBox> carveBoxes,
        List<Location> fixedTriggerBlocks,
        List<Location> triggerSideSources
) {
}
