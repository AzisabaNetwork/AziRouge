package net.azisaba.aziRouge.schematic;

import net.azisaba.aziRouge.dungeon.PlacedPiece;
import org.bukkit.World;

public interface SchematicAdapter {
    boolean isAvailable();

    String describeAvailability();

    void clearCache();

    void paste(World world, PlacedPiece piece) throws SchematicPlacementException;
}
