package net.azisaba.aziRouge.author;

import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.template.EntranceTemplate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateAuthoringService {
    private final JavaPlugin plugin;
    private final SelectionProvider selectionProvider;
    private final DebugLogger debugLogger;

    public TemplateAuthoringService(JavaPlugin plugin, SelectionProvider selectionProvider, DebugLogger debugLogger) {
        this.plugin = plugin;
        this.selectionProvider = selectionProvider;
        this.debugLogger = debugLogger;
    }

    public PieceAuthoringResult upsertPiece(Player player, String templatePath, String pieceId, String schematicPath, double weight)
            throws TemplateAuthoringException, SelectionLookupException {
        SelectionSnapshot selection = requirePlayerSelection(player);
        IntVector3 origin = selection.min();
        BlockBox bounds = BlockBox.fromPoints(selection.min().subtract(origin), selection.max().subtract(origin));
        Path file = resolveTemplateFile(templatePath);
        YamlConfiguration yaml = loadYaml(file);
        String base = "pieces." + pieceId;

        yaml.set(base + ".schematic", schematicPath);
        yaml.set(base + ".weight", weight);
        yaml.set(base + ".bounds.min", toList(bounds.min()));
        yaml.set(base + ".bounds.max", toList(bounds.max()));
        if (yaml.get(base + ".entrances") == null) {
            yaml.set(base + ".entrances", new ArrayList<>());
        }

        saveYaml(file, yaml);
        debugLogger.log("author", "piece_upserted", Map.of(
                "bounds", format(bounds),
                "origin", format(origin),
                "piece", pieceId,
                "template", file
        ));
        return new PieceAuthoringResult(file, origin, bounds);
    }

    public EntranceAuthoringResult upsertEntrance(Player player, String templatePath, String pieceId, String entranceId, Direction facing)
            throws TemplateAuthoringException, SelectionLookupException {
        SelectionSnapshot selection = requirePlayerSelection(player);
        IntVector3 origin = playerOrigin(player);
        BlockBox plane = BlockBox.fromPoints(selection.min().subtract(origin), selection.max().subtract(origin));
        EntranceTemplate entrance = new EntranceTemplate(entranceId, facing, plane.min(), plane.max());
        Path file = resolveTemplateFile(templatePath);
        YamlConfiguration yaml = loadYaml(file);
        ConfigurationSection pieceSection = yaml.getConfigurationSection("pieces." + pieceId);
        if (pieceSection == null) {
            throw new TemplateAuthoringException("Piece not found: " + pieceId);
        }

        BlockBox bounds = readBounds(pieceId, pieceSection);
        validateEntrance(pieceId, bounds, entrance);
        List<Map<String, Object>> entrances = normalizeMapList(yaml.getMapList("pieces." + pieceId + ".entrances"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", entranceId);
        data.put("facing", facing.name());
        data.put("point1", toList(plane.min()));
        data.put("point2", toList(plane.max()));

        boolean replaced = false;
        for (int index = 0; index < entrances.size(); index++) {
            if (entranceId.equals(String.valueOf(entrances.get(index).get("id")))) {
                entrances.set(index, data);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entrances.add(data);
        }

        yaml.set("pieces." + pieceId + ".entrances", entrances);
        saveYaml(file, yaml);
        debugLogger.log("author", "entrance_upserted", Map.of(
                "entrance", entranceId,
                "facing", facing,
                "origin", format(origin),
                "piece", pieceId,
                "plane", format(plane),
                "template", file
        ));
        return new EntranceAuthoringResult(file, origin, plane, facing);
    }

    public void removeEntrance(String templatePath, String pieceId, String entranceId) throws TemplateAuthoringException {
        Path file = resolveTemplateFile(templatePath);
        YamlConfiguration yaml = loadYaml(file);
        ConfigurationSection pieceSection = yaml.getConfigurationSection("pieces." + pieceId);
        if (pieceSection == null) {
            throw new TemplateAuthoringException("Piece not found: " + pieceId);
        }

        List<Map<String, Object>> entrances = normalizeMapList(yaml.getMapList("pieces." + pieceId + ".entrances"));
        boolean removed = entrances.removeIf(map -> entranceId.equals(String.valueOf(map.get("id"))));
        if (!removed) {
            throw new TemplateAuthoringException("Entrance not found: " + entranceId);
        }
        yaml.set("pieces." + pieceId + ".entrances", entrances);
        saveYaml(file, yaml);
        debugLogger.log("author", "entrance_removed", Map.of(
                "entrance", entranceId,
                "piece", pieceId,
                "template", file
        ));
    }

    private SelectionSnapshot requirePlayerSelection(Player player) throws SelectionLookupException, TemplateAuthoringException {
        if (!selectionProvider.isAvailable()) {
            throw new TemplateAuthoringException("WorldEdit selection is unavailable: " + selectionProvider.describeAvailability());
        }
        SelectionSnapshot selection = selectionProvider.getSelection(player);
        if (!player.getWorld().getName().equals(selection.worldName())) {
            throw new TemplateAuthoringException("Selection world must match the player's current world");
        }
        return selection;
    }

    private Path resolveTemplateFile(String rawPath) throws TemplateAuthoringException {
        try {
            Path file = Path.of(rawPath);
            if (!file.isAbsolute()) {
                file = plugin.getDataFolder().toPath().resolve(rawPath);
            }
            return file.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new TemplateAuthoringException("Invalid template path: " + rawPath, ex);
        }
    }

    private YamlConfiguration loadYaml(Path file) {
        return Files.exists(file) ? YamlConfiguration.loadConfiguration(file.toFile()) : new YamlConfiguration();
    }

    private void saveYaml(Path file, YamlConfiguration yaml) throws TemplateAuthoringException {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            yaml.save(file.toFile());
        } catch (IOException ex) {
            throw new TemplateAuthoringException("Failed to save template file: " + file, ex);
        }
    }

    private IntVector3 playerOrigin(Player player) {
        return new IntVector3(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ()
        );
    }

    private BlockBox readBounds(String pieceId, ConfigurationSection pieceSection) throws TemplateAuthoringException {
        ConfigurationSection boundsSection = pieceSection.getConfigurationSection("bounds");
        if (boundsSection == null) {
            throw new TemplateAuthoringException("Piece " + pieceId + " has no bounds");
        }
        return BlockBox.fromPoints(
                readVector(boundsSection.getList("min"), "bounds.min", pieceId),
                readVector(boundsSection.getList("max"), "bounds.max", pieceId)
        );
    }

    private void validateEntrance(String pieceId, BlockBox bounds, EntranceTemplate entrance) throws TemplateAuthoringException {
        try {
            entrance.validate(pieceId);
        } catch (IllegalArgumentException ex) {
            throw new TemplateAuthoringException(ex.getMessage(), ex);
        }
        BlockBox opening = entrance.planeBox().extend(entrance.facing().opposite(), entrance.width() - 1);
        if (!contains(bounds, entrance.planeBox())) {
            throw new TemplateAuthoringException("Entrance plane is outside piece bounds");
        }
        if (!contains(bounds, opening)) {
            throw new TemplateAuthoringException("Entrance opening depth exceeds piece bounds");
        }
    }

    private boolean contains(BlockBox outer, BlockBox inner) {
        return outer.minX() <= inner.minX() && outer.maxX() >= inner.maxX()
                && outer.minY() <= inner.minY() && outer.maxY() >= inner.maxY()
                && outer.minZ() <= inner.minZ() && outer.maxZ() >= inner.maxZ();
    }

    private IntVector3 readVector(List<?> raw, String field, String pieceId) throws TemplateAuthoringException {
        if (raw == null || raw.size() != 3) {
            throw new TemplateAuthoringException("Piece " + pieceId + " has invalid " + field);
        }
        return new IntVector3(parseNumber(raw.get(0), field), parseNumber(raw.get(1), field), parseNumber(raw.get(2), field));
    }

    private int parseNumber(Object raw, String field) throws TemplateAuthoringException {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new TemplateAuthoringException("Invalid integer in " + field);
        }
    }

    private List<Map<String, Object>> normalizeMapList(List<Map<?, ?>> raw) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<?, ?> map : raw) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            list.add(normalized);
        }
        return list;
    }

    private List<Integer> toList(IntVector3 vector) {
        return List.of(vector.x(), vector.y(), vector.z());
    }

    private String format(IntVector3 vector) {
        return vector.x() + "," + vector.y() + "," + vector.z();
    }

    private String format(BlockBox box) {
        return format(box.min()) + "->" + format(box.max());
    }
}
