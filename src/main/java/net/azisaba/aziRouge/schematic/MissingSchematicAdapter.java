package net.azisaba.aziRouge.schematic;

import net.azisaba.aziRouge.dungeon.PlacedPiece;
import org.bukkit.World;

public final class MissingSchematicAdapter implements SchematicAdapter {
    private final String reason;

    public MissingSchematicAdapter(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describeAvailability() {
        return reason;
    }

    @Override
    public void clearCache() {
    }

    @Override
    public void paste(World world, PlacedPiece piece) throws SchematicPlacementException {
        throw new SchematicPlacementException(reason);
    }
}
