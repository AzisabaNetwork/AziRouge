package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.TreasureSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class TreasurePopulator {
    private final AziRouge plugin;
    private final LootTable lootTable = new LootTable();

    public TreasurePopulator(AziRouge plugin) {
        this.plugin = plugin;
    }

    public int populateRoom(World world, PlacedPiece piece) {
        TreasureSettings settings = plugin.settings().azirouge().treasure();
        Random random = ThreadLocalRandom.current();
        double spawnChance = settings.spawnChance(piece.depth());
        double roll = random.nextDouble();
        if (roll > spawnChance) {
            plugin.debugLogger().log("treasure", "skipped_chance", Map.of(
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
        TreasureSettings settings = plugin.settings().azirouge().treasure();
        java.util.List<ItemStack> rewards = lootTable.roll(piece.depth(), settings, random);
        if (rewards.isEmpty()) {
            plugin.debugLogger().log("treasure", "empty_loot", Map.of(
                    "depth", piece.depth(),
                    "piece", piece.template().id()
            ));
            return 0;
        }

        int spawned = 0;
        for (ItemStack reward : rewards) {
            if (reward == null || reward.getType() == Material.AIR) {
                plugin.debugLogger().log("treasure", "invalid_reward", Map.of(
                        "depth", piece.depth(),
                        "piece", piece.template().id()
                ));
                continue;
            }
            Block placementBlock = RoomPlacementHelper.findPlacementBlock(world, piece.worldBounds(), random);
            if (placementBlock == null) {
                plugin.debugLogger().log("treasure", "no_placement", Map.of(
                        "bounds", piece.worldBounds(),
                        "depth", piece.depth(),
                        "diagnostic", RoomPlacementHelper.diagnosePlacementFailure(world, piece.worldBounds()),
                        "piece", piece.template().id(),
                        "world", world.getName()
                ));
                break;
            }
            spawnTreasureDisplay(placementBlock, withSellPriceLore(reward));
            spawned++;
            plugin.debugLogger().log("treasure", "spawned", Map.of(
                    "amount", reward.getAmount(),
                    "depth", piece.depth(),
                    "material", reward.getType(),
                    "piece", piece.template().id(),
                    "x", placementBlock.getX(),
                    "y", placementBlock.getY(),
                    "z", placementBlock.getZ()
            ));
        }
        return spawned;
    }

    private ItemStack withSellPriceLore(ItemStack reward) {
        ItemStack item = reward.clone();
        Long price = plugin.settings().economy().sellPrices().get(item.getType());
        if (price == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.lore(List.of(Component.text(plugin.messages().format("treasure.sell-price", "Sell price: {price}", "price", price))));
            item.setItemMeta(meta);
        }
        return item;
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
