package net.azisaba.aziRouge.template;

import net.azisaba.aziRouge.debug.DebugLogger;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.Direction;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TemplateManager {
    private final JavaPlugin plugin;
    private final DebugLogger debugLogger;

    public TemplateManager(JavaPlugin plugin, DebugLogger debugLogger) {
        this.plugin = plugin;
        this.debugLogger = debugLogger;
    }

    public LoadedTemplates load(List<String> patterns) throws TemplateLoadException {
        try {
            List<Path> templateFiles = resolveTemplateFiles(patterns);
            if (templateFiles.isEmpty()) {
                throw new TemplateLoadException("No template files matched patterns: " + patterns);
            }

            Map<String, PieceTemplate> pieces = new LinkedHashMap<>();
            for (Path templateFile : templateFiles) {
                loadSingleTemplate(templateFile, pieces);
            }
            if (pieces.isEmpty()) {
                throw new TemplateLoadException("Templates contained no pieces: " + templateFiles);
            }
            debugLogger.log("template", "load_complete", Map.of(
                    "files", templateFiles.size(),
                    "patterns", patterns,
                    "pieces", pieces.size()
            ));
            return new LoadedTemplates(Map.copyOf(pieces), List.copyOf(templateFiles));
        } catch (IOException ex) {
            throw new TemplateLoadException("Failed to read template files", ex);
        }
    }

    private List<Path> resolveTemplateFiles(List<String> patterns) throws IOException {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Set<Path> resolved = new LinkedHashSet<>();
        List<Path> candidates;
        try (var stream = Files.walk(dataFolder)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        }
        for (String pattern : patterns) {
            Path rawPath = tryCreatePath(pattern);
            if (rawPath != null && rawPath.isAbsolute()) {
                Path normalized = rawPath.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    resolved.add(normalized);
                    continue;
                }
            }
            if (looksLikeAbsolutePattern(pattern)) {
                String matcher = "glob:" + pattern.replace("\\", "/");
                for (Path candidate : candidates) {
                    if (FileSystems.getDefault().getPathMatcher(matcher).matches(Path.of(candidate.toString().replace("\\", "/")))) {
                        resolved.add(candidate);
                    }
                }
                continue;
            }

            String matcher = "glob:" + pattern.replace("\\", "/");
            for (Path candidate : candidates) {
                Path relative = dataFolder.relativize(candidate);
                if (FileSystems.getDefault().getPathMatcher(matcher).matches(Path.of(relative.toString().replace("\\", "/")))) {
                    resolved.add(candidate);
                }
            }
        }
        return List.copyOf(resolved);
    }

    private void loadSingleTemplate(Path templateFile, Map<String, PieceTemplate> pieces) throws TemplateLoadException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(templateFile.toFile());
        ConfigurationSection pieceSection = yaml.getConfigurationSection("pieces");
        if (pieceSection == null) {
            throw new TemplateLoadException("Template file has no pieces section: " + templateFile);
        }

        for (String pieceId : pieceSection.getKeys(false)) {
            if (pieces.containsKey(pieceId)) {
                throw new TemplateLoadException("Duplicate piece id " + pieceId + " in " + templateFile);
            }

            ConfigurationSection section = pieceSection.getConfigurationSection(pieceId);
            if (section == null) {
                throw new TemplateLoadException("Piece section missing for " + pieceId + " in " + templateFile);
            }
            pieces.put(pieceId, parsePiece(templateFile, pieceId, section));
        }
    }

    private PieceTemplate parsePiece(Path templateFile, String pieceId, ConfigurationSection section) throws TemplateLoadException {
        String schematicValue = section.getString("schematic");
        if (schematicValue == null || schematicValue.isBlank()) {
            throw new TemplateLoadException("Piece " + pieceId + " in " + templateFile + " is missing schematic");
        }
        Path schematicPath = resolveSchematicPath(templateFile, schematicValue.trim());
        BlockBox bounds = parseBox(section.getConfigurationSection("bounds"), "Piece " + pieceId + " bounds");
        List<EntranceTemplate> entrances = parseEntrances(pieceId, section);
        if (entrances.isEmpty()) {
            throw new TemplateLoadException("Piece " + pieceId + " in " + templateFile + " has no entrances");
        }
        validateEntrancesWithinBounds(pieceId, bounds, entrances);
        List<EnemySocketTemplate> enemySockets = parseEnemySockets(section);
        List<String> deniedAdjacentPieces = parseDeniedAdjacentPieces(pieceId, section);
        return new PieceTemplate(
                pieceId,
                templateFile,
                schematicPath,
                Math.max(0.0001D, section.getDouble("weight", 1.0D)),
                bounds,
                List.copyOf(entrances),
                List.copyOf(enemySockets),
                List.copyOf(deniedAdjacentPieces)
        );
    }

    private Path resolveSchematicPath(Path templateFile, String rawPath) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        Path relativeToTemplate = templateFile.getParent().resolve(rawPath).normalize();
        if (Files.exists(relativeToTemplate)) {
            return relativeToTemplate.toAbsolutePath().normalize();
        }
        return plugin.getDataFolder().toPath().resolve(rawPath).toAbsolutePath().normalize();
    }

    private List<EntranceTemplate> parseEntrances(String pieceId, ConfigurationSection section) throws TemplateLoadException {
        List<Map<?, ?>> maps = section.getMapList("entrances");
        List<EntranceTemplate> entrances = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            String id = requireString(map.get("id"), "entrance id", pieceId);
            Direction facing;
            try {
                facing = Direction.valueOf(requireString(map.get("facing"), "entrance facing", pieceId).toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new TemplateLoadException("Piece " + pieceId + " entrance " + id + " has invalid facing");
            }
            IntVector3 point1 = parseVector(map.get("point1"), "point1", pieceId);
            IntVector3 point2 = parseVector(map.get("point2"), "point2", pieceId);
            EntranceTemplate entrance = new EntranceTemplate(id, facing, point1, point2);
            try {
                entrance.validate(pieceId);
            } catch (IllegalArgumentException ex) {
                throw new TemplateLoadException(ex.getMessage(), ex);
            }
            entrances.add(entrance);
        }
        return entrances;
    }

    private List<EnemySocketTemplate> parseEnemySockets(ConfigurationSection section) throws TemplateLoadException {
        List<Map<?, ?>> maps = section.getMapList("enemy-sockets");
        List<EnemySocketTemplate> sockets = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            String id = map.containsKey("id") ? String.valueOf(map.get("id")) : "socket";
            IntVector3 position = parseVector(map.get("position"), "position", id);
            String tag = map.containsKey("tag") ? String.valueOf(map.get("tag")) : "default";
            sockets.add(new EnemySocketTemplate(id, position, tag));
        }
        return sockets;
    }

    private List<String> parseDeniedAdjacentPieces(String pieceId, ConfigurationSection section) throws TemplateLoadException {
        if (section.contains("allowed-adjacent-pieces")) {
            throw new TemplateLoadException("Piece " + pieceId + " uses deprecated allowed-adjacent-pieces. Replace it with denied-adjacent-pieces.");
        }
        List<String> values = new ArrayList<>();
        for (String raw : section.getStringList("denied-adjacent-pieces")) {
            String value = raw == null ? "" : raw.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private void validateEntrancesWithinBounds(String pieceId, BlockBox bounds, List<EntranceTemplate> entrances) throws TemplateLoadException {
        for (EntranceTemplate entrance : entrances) {
            BlockBox plane = entrance.planeBox();
            BlockBox opening = plane.extend(entrance.facing().opposite(), entrance.width() - 1);
            if (!contains(bounds, plane)) {
                throw new TemplateLoadException("Piece " + pieceId + " entrance " + entrance.id() + " is outside bounds");
            }
            if (!contains(bounds, opening)) {
                throw new TemplateLoadException("Piece " + pieceId + " entrance " + entrance.id() + " opening depth exceeds bounds");
            }
        }
    }

    private boolean contains(BlockBox outer, BlockBox inner) {
        return outer.minX() <= inner.minX() && outer.maxX() >= inner.maxX()
                && outer.minY() <= inner.minY() && outer.maxY() >= inner.maxY()
                && outer.minZ() <= inner.minZ() && outer.maxZ() >= inner.maxZ();
    }

    private BlockBox parseBox(ConfigurationSection section, String label) throws TemplateLoadException {
        if (section == null) {
            throw new TemplateLoadException(label + " is missing");
        }
        return BlockBox.fromPoints(
                parseVector(section.getList("min"), "min", label),
                parseVector(section.getList("max"), "max", label)
        );
    }

    private IntVector3 parseVector(Object raw, String field, String label) throws TemplateLoadException {
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            throw new TemplateLoadException(label + " field " + field + " must be a 3-element list");
        }
        return new IntVector3(parseInt(list.get(0), field, label), parseInt(list.get(1), field, label), parseInt(list.get(2), field, label));
    }

    private String requireString(Object value, String field, String label) throws TemplateLoadException {
        if (value == null) {
            throw new TemplateLoadException(label + " field " + field + " is missing");
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new TemplateLoadException(label + " field " + field + " is blank");
        }
        return text;
    }

    private int parseInt(Object value, String field, String label) throws TemplateLoadException {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new TemplateLoadException(label + " field " + field + " contains a non-integer value");
        }
    }

    private Path tryCreatePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    private boolean looksLikeAbsolutePattern(String value) {
        return value.startsWith("\\\\")
                || value.startsWith("//")
                || value.matches("^[A-Za-z]:[\\\\/].*");
    }
}
