package net.azisaba.aziRouge.template;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record LoadedTemplates(Map<String, PieceTemplate> pieces, List<Path> sourceFiles) {
}
