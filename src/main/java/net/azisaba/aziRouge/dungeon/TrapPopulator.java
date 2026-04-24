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

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class TrapPopulator {
    private final AziRouge plugin;

    public TrapPopulator(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void populateRoom(World world, PlacedPiece piece) {
        TrapSettings settings = plugin.settings().azirouge().traps();
        Random random = ThreadLocalRandom.current();
        if (random.nextDouble() > settings.spawnChance(piece.depth())) {
            return;
        }

        TrapDefinitionSettings definition = settings.selectRandomDefinition(random, piece.depth());
        if (definition == null) {
            return;
        }

        Block placementBlock = RoomPlacementHelper.findPlacementBlock(world, piece.worldBounds(), random);
        if (placementBlock == null) {
            return;
        }

        Material material = Material.matchMaterial(definition.displayMaterial());
        if (material == null || !material.isBlock()) {
            plugin.getLogger().warning("Invalid trap display material: " + definition.displayMaterial());
            return;
        }

        Location displayLocation = placementBlock.getLocation();
        if (!(world.spawnEntity(displayLocation, EntityType.BLOCK_DISPLAY) instanceof BlockDisplay display)) {
            plugin.getLogger().warning("Failed to create trap display at " + displayLocation);
            return;
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
    }
}
