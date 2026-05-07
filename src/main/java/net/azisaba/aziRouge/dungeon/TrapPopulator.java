package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.TrapDefinitionSettings;
import net.azisaba.aziRouge.config.TrapSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class TrapPopulator {
    private final AziRouge plugin;

    public TrapPopulator(AziRouge plugin) {
        this.plugin = plugin;
    }

    public int populateRoom(World world, PlacedPiece piece) {
        TrapSettings settings = plugin.settings().azirouge().traps();
        Random random = ThreadLocalRandom.current();
        double spawnChance = settings.spawnChance(piece.depth());
        double roll = random.nextDouble();
        if (roll > spawnChance) {
            plugin.debugLogger().log("trap", "skipped_chance", Map.of(
                    "chance", spawnChance,
                    "depth", piece.depth(),
                    "piece", piece.template().id(),
                    "roll", roll
            ));
            return 0;
        }
        return populateGuaranteedRoom(world, piece, random);
    }

    public int populateGuaranteedRoom(World world, PlacedPiece piece, Random random) {
        TrapSettings settings = plugin.settings().azirouge().traps();
        TrapDefinitionSettings definition = settings.selectRandomDefinition(random, piece.depth());
        if (definition == null) {
            plugin.debugLogger().log("trap", "no_definition", Map.of(
                    "depth", piece.depth(),
                    "definitions", settings.definitions().size(),
                    "piece", piece.template().id()
            ));
            return 0;
        }

        Block placementBlock = RoomPlacementHelper.findPlacementBlock(world, piece.worldBounds(), random);
        if (placementBlock == null) {
            plugin.debugLogger().log("trap", "no_placement", Map.of(
                    "bounds", piece.worldBounds(),
                    "definition", definition.key(),
                    "depth", piece.depth(),
                    "diagnostic", RoomPlacementHelper.diagnosePlacementFailure(world, piece.worldBounds()),
                    "piece", piece.template().id(),
                    "world", world.getName()
            ));
            return 0;
        }

        Material material = Material.matchMaterial(definition.displayMaterial());
        if (material == null || !material.isBlock()) {
            plugin.getLogger().warning("Invalid trap display material: " + definition.displayMaterial());
            return 0;
        }

        Location displayLocation = placementBlock.getLocation();
        if (!(world.spawnEntity(displayLocation, EntityType.BLOCK_DISPLAY) instanceof BlockDisplay display)) {
            plugin.getLogger().warning("Failed to create trap display at " + displayLocation);
            return 0;
        }

        display.setBlock(material.createBlockData());
        display.setGravity(false);
        display.setInvulnerable(true);
        display.addScoreboardTag(DisplayGimmickKeys.TRAP_DISPLAY_TAG);
        display.getPersistentDataContainer().set(
                DisplayGimmickKeys.trapKey(plugin),
                PersistentDataType.STRING,
                definition.key()
        );
        plugin.debugLogger().log("trap", "spawned", Map.of(
                "definition", definition.key(),
                "depth", piece.depth(),
                "material", material,
                "piece", piece.template().id(),
                "x", placementBlock.getX(),
                "y", placementBlock.getY(),
                "z", placementBlock.getZ()
        ));
        return 1;
    }
}
