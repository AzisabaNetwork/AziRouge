package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class SessionWorldService {
    private static final List<String> TEMPLATE_COPY_EXCLUDED_DIRECTORIES = List.of("playerdata", "stats", "advancements");
    private static final List<String> TEMPLATE_COPY_EXCLUDED_FILES = List.of("uid.dat", "session.lock");

    private final AziRouge plugin;

    SessionWorldService(AziRouge plugin) {
        this.plugin = plugin;
    }

    void cleanupLeftoverWorldFoldersOnStartup() {
        if (!plugin.settings().sessions().cleanupLeftoverWorldsOnStartup()) {
            return;
        }

        Path worldContainer = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path templatePath = plugin.settings().sessions().homeTemplateWorldPath().toAbsolutePath().normalize();
        File[] children = worldContainer.toFile().listFiles(File::isDirectory);
        if (children == null) {
            return;
        }

        String prefix = plugin.settings().sessions().worldNamePrefix();
        for (File child : children) {
            String worldName = child.getName();
            Path path = child.toPath().toAbsolutePath().normalize();
            if (!worldName.startsWith(prefix) || path.equals(templatePath) || Bukkit.getWorld(worldName) != null) {
                continue;
            }
            deleteWorldFolder(path, worldName);
        }
    }

    Path sessionWorldFolder(String worldName) {
        return plugin.getServer().getWorldContainer().toPath().resolve(worldName).toAbsolutePath().normalize();
    }

    void copyTemplateWorld(Path destination, String worldName) {
        Path source = plugin.settings().sessions().homeTemplateWorldPath().toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("Home template world folder not found: " + source);
        }
        if (source.equals(target) || target.startsWith(source)) {
            throw new IllegalStateException("Home template world path must not point to a session world folder: " + source);
        }
        if (Files.exists(target)) {
            deleteWorldFolder(target, worldName);
        }

        try {
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(dir);
                    if (isExcludedTemplateDirectory(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(file);
                    if (!isExcludedTemplateFile(relative)) {
                        Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            deleteWorldFolder(target, worldName);
            throw new IllegalStateException("Failed to copy home template world for session " + worldName + ": " + ex.getMessage(), ex);
        }
    }

    void cleanupWorld(World world) {
        File worldFolder = world.getWorldFolder();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().severe("Failed to unload temporary session world " + world.getName() + " after startup error.");
            return;
        }
        deleteWorldFolder(worldFolder.toPath(), world.getName());
    }

    void deleteWorldFolder(Path root, String worldName) {
        if (!Files.exists(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to delete session world folder " + worldName + ": " + ex.getMessage());
        }
    }

    private boolean isExcludedTemplateDirectory(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        return TEMPLATE_COPY_EXCLUDED_DIRECTORIES.contains(relative.getName(0).toString().toLowerCase(Locale.ROOT));
    }

    private boolean isExcludedTemplateFile(Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }
        return TEMPLATE_COPY_EXCLUDED_FILES.contains(relative.getFileName().toString().toLowerCase(Locale.ROOT));
    }
}
