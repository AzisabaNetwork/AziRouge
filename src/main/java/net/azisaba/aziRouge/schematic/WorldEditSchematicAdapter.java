package net.azisaba.aziRouge.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.dungeon.PlacedPiece;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldEditSchematicAdapter implements SchematicAdapter {
    private final JavaPlugin plugin;
    private final DebugLogger debugLogger;
    private final Map<Path, Clipboard> clipboardCache = new ConcurrentHashMap<>();

    public WorldEditSchematicAdapter(JavaPlugin plugin, DebugLogger debugLogger) {
        this.plugin = plugin;
        this.debugLogger = debugLogger;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String describeAvailability() {
        return "worldedit";
    }

    @Override
    public void clearCache() {
        clipboardCache.clear();
    }

    @Override
    public void paste(World world, PlacedPiece piece) throws SchematicPlacementException {
        Clipboard clipboard = loadClipboard(piece.template().schematicPath());
        IntVector3 pasteTarget = resolvePasteTarget(piece, clipboard);
        BlockVector3 target = BlockVector3.at(pasteTarget.x(), pasteTarget.y(), pasteTarget.z());
        debugLogger.log("schematic", "paste_begin", Map.of(
                "anchor", piece.origin().x() + "," + piece.origin().y() + "," + piece.origin().z(),
                "piece", piece.template().id(),
                "rotation", piece.rotation().degrees(),
                "schematic", piece.template().schematicPath(),
                "target", pasteTarget.x() + "," + pasteTarget.y() + "," + pasteTarget.z()
        ));
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            ClipboardHolder holder = new ClipboardHolder(clipboard);
            if (piece.rotation().degrees() != 0) {
                holder.setTransform(holder.getTransform().combine(new AffineTransform().rotateY(piece.rotation().degrees())));
            }
            Operation operation = holder.createPaste(session)
                    .to(target)
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(operation);
        } catch (Exception ex) {
            throw new SchematicPlacementException("Failed to paste schematic for piece " + piece.template().id(), ex);
        }
        debugLogger.log("schematic", "paste_complete", Map.of(
                "dimensions", clipboard.getDimensions(),
                "piece", piece.template().id(),
                "world", world.getName()
        ));
    }

    private Clipboard loadClipboard(Path path) throws SchematicPlacementException {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return clipboardCache.computeIfAbsent(normalized, this::readClipboardUnchecked);
        } catch (RuntimeException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new SchematicPlacementException("Failed to load schematic: " + path, cause);
        }
    }

    private Clipboard readClipboardUnchecked(Path path) {
        ClipboardFormat format = ClipboardFormats.findByFile(path.toFile());
        if (format == null) {
            throw new IllegalStateException("Unsupported schematic format: " + path);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException("Schematic file not found: " + path);
        }
        try (InputStream inputStream = Files.newInputStream(path); ClipboardReader reader = format.getReader(inputStream)) {
            Clipboard clipboard = reader.read();
            BlockVector3 clipboardMin = clipboard.getRegion().getMinimumPoint();
            clipboard.setOrigin(clipboardMin);
            debugLogger.log("schematic", "loaded", Map.of(
                    "clipboardMin", clipboardMin,
                    "dimensions", clipboard.getDimensions(),
                    "origin", clipboard.getOrigin(),
                    "path", path
            ));
            return clipboard;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read schematic: " + path, ex);
        }
    }

    private IntVector3 resolvePasteTarget(PlacedPiece piece, Clipboard clipboard) {
        int sizeX = clipboard.getDimensions().x();
        int sizeZ = clipboard.getDimensions().z();
        return switch (piece.rotation()) {
            case NONE -> piece.origin();
            case CLOCKWISE_90 -> new IntVector3(piece.origin().x(), piece.origin().y(), piece.origin().z() + sizeX - 1);
            case CLOCKWISE_180 -> new IntVector3(piece.origin().x() + sizeX - 1, piece.origin().y(), piece.origin().z() + sizeZ - 1);
            case CLOCKWISE_270 -> new IntVector3(piece.origin().x() + sizeZ - 1, piece.origin().y(), piece.origin().z());
        };
    }
}
