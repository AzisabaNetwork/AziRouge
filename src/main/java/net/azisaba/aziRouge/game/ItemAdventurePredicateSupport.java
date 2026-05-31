package net.azisaba.aziRouge.game;

import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.set.RegistrySet;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

public final class ItemAdventurePredicateSupport {
    private ItemAdventurePredicateSupport() {
    }

    public static void setCanBreak(ItemStack item, Set<Material> materials) {
        if (materials.isEmpty()) {
            item.unsetData(DataComponentTypes.CAN_BREAK);
            return;
        }
        List<BlockType> blockTypes = materials.stream()
                .filter(Material::isBlock)
                .map(Material::asBlockType)
                .toList();
        if (blockTypes.isEmpty()) {
            item.unsetData(DataComponentTypes.CAN_BREAK);
            return;
        }

        BlockPredicate predicate = BlockPredicate.predicate()
                .blocks(RegistrySet.keySetFromValues(RegistryKey.BLOCK, blockTypes))
                .build();
        item.setData(DataComponentTypes.CAN_BREAK, ItemAdventurePredicate.itemAdventurePredicate(List.of(predicate)));
    }

    public static boolean canBreak(ItemStack item, Block block) {
        if (item == null || item.getType().isAir() || !item.hasData(DataComponentTypes.CAN_BREAK)) {
            return false;
        }
        ItemAdventurePredicate predicate = item.getData(DataComponentTypes.CAN_BREAK);
        if (predicate == null) {
            return false;
        }
        BlockType blockType = block.getType().asBlockType();
        return predicate.predicates().stream()
                .anyMatch(blockPredicate -> blockPredicate.blocks().resolve(Registry.BLOCK).contains(blockType));
    }
}
