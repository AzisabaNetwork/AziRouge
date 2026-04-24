package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.TreasureSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class TreasurePopulator {
    private final AziRouge plugin;
    private final LootTable lootTable = new LootTable();

    public TreasurePopulator(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void populateRoom(World world, PlacedPiece piece) {
        TreasureSettings settings = plugin.settings().azirouge().treasure();
        Random random = ThreadLocalRandom.current();
        if (random.nextDouble() > settings.spawnChance(piece.depth())) {
            return;
        }

        java.util.List<ItemStack> rewards = lootTable.roll(piece.depth(), settings, random);
        if (rewards.isEmpty()) {
            return;
        }

        for (ItemStack reward : rewards) {
            if (reward == null || reward.getType() == Material.AIR) {
                continue;
            }
            Block placementBlock = RoomPlacementHelper.findPlacementBlock(world, piece.worldBounds(), random);
            if (placementBlock == null) {
                break;
            }
            spawnTreasureDisplay(placementBlock, reward);
        }
    }

    private void spawnTreasureDisplay(Block placementBlock, ItemStack reward) {
        Location displayLocation = new Location(
                placementBlock.getWorld(),
                placementBlock.getX() + 0.5D,
                placementBlock.getY() + 0.15D,
                placementBlock.getZ() + 0.5D
        );

        if (!(placementBlock.getWorld().spawnEntity(displayLocation, EntityType.ITEM_DISPLAY) instanceof ItemDisplay display)) {
            plugin.getLogger().warning("Failed to create treasure item display at " + displayLocation);
            return;
        }

        display.setItemStack(reward.clone());
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.addScoreboardTag(DisplayGimmickKeys.TREASURE_DISPLAY_TAG);

        Location interactionLocation = displayLocation.clone().add(0.0D, 0.35D, 0.0D);
        if (!(placementBlock.getWorld().spawnEntity(interactionLocation, EntityType.INTERACTION) instanceof Interaction interaction)) {
            display.remove();
            plugin.getLogger().warning("Failed to create treasure interaction at " + interactionLocation);
            return;
        }

        interaction.setInteractionWidth(0.9F);
        interaction.setInteractionHeight(0.75F);
        interaction.setResponsive(true);
        interaction.setGravity(false);
        interaction.setInvulnerable(true);
        interaction.addScoreboardTag(DisplayGimmickKeys.TREASURE_INTERACTION_TAG);
        interaction.getPersistentDataContainer().set(
                DisplayGimmickKeys.linkedDisplayKey(plugin),
                PersistentDataType.STRING,
                display.getUniqueId().toString()
        );
    }
}
