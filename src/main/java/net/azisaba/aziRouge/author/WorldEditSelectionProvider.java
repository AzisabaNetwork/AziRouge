package net.azisaba.aziRouge.author;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.entity.Player;

public final class WorldEditSelectionProvider implements SelectionProvider {
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String describeAvailability() {
        return "worldedit";
    }

    @Override
    public SelectionSnapshot getSelection(Player player) throws SelectionLookupException {
        try {
            com.sk89q.worldedit.entity.Player actor = BukkitAdapter.adapt(player);
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
            World selectionWorld = BukkitAdapter.adapt(player.getWorld());
            Region region = session.getSelection(selectionWorld);
            return new SelectionSnapshot(
                    player.getWorld().getName(),
                    toVector(region.getMinimumPoint()),
                    toVector(region.getMaximumPoint())
            );
        } catch (IncompleteRegionException ex) {
            throw new SelectionLookupException("WorldEdit selection is incomplete", ex);
        } catch (Exception ex) {
            throw new SelectionLookupException("Failed to read WorldEdit selection", ex);
        }
    }

    private IntVector3 toVector(com.sk89q.worldedit.math.BlockVector3 vector) {
        return new IntVector3(vector.x(), vector.y(), vector.z());
    }
}
