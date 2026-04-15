package net.azisaba.aziRouge.dungeon;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

public final class LootTable {
    private final Map<Tier, List<WeightedLootEntry>> lootByTier = Map.of(
            Tier.TIER_1, tierOneEntries(),
            Tier.TIER_2, tierTwoEntries(),
            Tier.TIER_3, tierThreeEntries()
    );

    public Tier tierForDepth(int depth) {
        if (depth <= 2) {
            return Tier.TIER_1;
        }
        if (depth <= 5) {
            return Tier.TIER_2;
        }
        return Tier.TIER_3;
    }

    public List<ItemStack> roll(Tier tier, Random random) {
        int rolls = switch (tier) {
            case TIER_1 -> 2 + random.nextInt(3);
            case TIER_2 -> 3 + random.nextInt(3);
            case TIER_3 -> 4 + random.nextInt(3);
        };
        List<WeightedLootEntry> entries = lootByTier.get(tier);
        List<ItemStack> results = new ArrayList<>(rolls);
        for (int index = 0; index < rolls; index++) {
            results.add(select(entries, random).createItem(random));
        }
        return results;
    }

    private WeightedLootEntry select(List<WeightedLootEntry> entries, Random random) {
        int totalWeight = 0;
        for (WeightedLootEntry entry : entries) {
            totalWeight += entry.weight();
        }
        int cursor = random.nextInt(totalWeight);
        for (WeightedLootEntry entry : entries) {
            cursor -= entry.weight();
            if (cursor < 0) {
                return entry;
            }
        }
        return entries.get(0);
    }

    private List<WeightedLootEntry> tierOneEntries() {
        return List.of(
                entry(14, random -> stack(Material.BREAD, 2, 4, random)),
                entry(12, random -> stack(Material.COOKED_BEEF, 2, 4, random)),
                entry(10, random -> stack(Material.WOODEN_SWORD)),
                entry(10, random -> stack(Material.WOODEN_AXE)),
                entry(8, random -> stack(Material.LEATHER_HELMET)),
                entry(8, random -> stack(Material.LEATHER_CHESTPLATE)),
                entry(6, random -> stack(Material.TORCH, 8, 16, random))
        );
    }

    private List<WeightedLootEntry> tierTwoEntries() {
        return List.of(
                entry(12, random -> stack(Material.IRON_SWORD)),
                entry(11, random -> stack(Material.IRON_AXE)),
                entry(10, random -> stack(Material.IRON_HELMET)),
                entry(10, random -> stack(Material.IRON_CHESTPLATE)),
                entry(8, random -> potion(PotionType.HEALING)),
                entry(7, random -> potion(PotionType.SWIFTNESS)),
                entry(6, random -> stack(Material.GOLDEN_APPLE)),
                entry(5, random -> stack(Material.ARROW, 8, 16, random))
        );
    }

    private List<WeightedLootEntry> tierThreeEntries() {
        return List.of(
                entry(11, random -> stack(Material.DIAMOND_SWORD)),
                entry(10, random -> stack(Material.DIAMOND_AXE)),
                entry(9, random -> stack(Material.DIAMOND_HELMET)),
                entry(9, random -> stack(Material.DIAMOND_CHESTPLATE)),
                entry(8, random -> stack(Material.GOLDEN_APPLE, 1, 2, random)),
                entry(7, random -> enchantedBook("sharpness", 3)),
                entry(7, random -> enchantedBook("protection", 3)),
                entry(5, random -> enchantedBook("unbreaking", 3)),
                entry(5, random -> potion(PotionType.STRENGTH))
        );
    }

    private WeightedLootEntry entry(int weight, Function<Random, ItemStack> factory) {
        return new WeightedLootEntry(weight, factory);
    }

    private static ItemStack stack(Material material) {
        return new ItemStack(material, 1);
    }

    private static ItemStack stack(Material material, int minAmount, int maxAmount, Random random) {
        int amount = minAmount == maxAmount ? minAmount : minAmount + random.nextInt(maxAmount - minAmount + 1);
        return new ItemStack(material, amount);
    }

    private static ItemStack potion(PotionType potionType) {
        ItemStack itemStack = new ItemStack(Material.POTION, 1);
        PotionMeta meta = (PotionMeta) itemStack.getItemMeta();
        if (meta != null) {
            meta.setBasePotionType(potionType);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private static ItemStack enchantedBook(String enchantmentKey, int level) {
        ItemStack itemStack = new ItemStack(Material.ENCHANTED_BOOK, 1);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) itemStack.getItemMeta();
        if (meta != null) {
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantmentKey));
            if (enchantment != null) {
                meta.addStoredEnchant(enchantment, level, true);
            }
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    public enum Tier {
        TIER_1,
        TIER_2,
        TIER_3
    }

    private record WeightedLootEntry(int weight, Function<Random, ItemStack> factory) {
        private ItemStack createItem(Random random) {
            return factory.apply(random).clone();
        }
    }
}
