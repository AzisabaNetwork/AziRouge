package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.config.ChestLootEntrySettings;
import net.azisaba.aziRouge.config.ChestLootTierSettings;
import net.azisaba.aziRouge.config.ChestSettings;
import net.azisaba.aziRouge.config.TreasureSettings;
import net.azisaba.aziRouge.game.ItemAdventurePredicateSupport;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class LootTable {
    public List<ItemStack> roll(int depth, ChestSettings chestSettings, Random random) {
        ChestLootTierSettings tier = chestSettings.tierForDepth(depth);
        if (tier.entries().isEmpty()) {
            return List.of();
        }

        int rolls = randomRollCount(tier, random);
        List<ItemStack> results = new ArrayList<>(rolls);
        for (int index = 0; index < rolls; index++) {
            ItemStack itemStack = createItem(select(tier.entries(), random), random);
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                results.add(itemStack);
            }
        }
        return results;
    }

    public ItemStack rollSingle(int depth, TreasureSettings treasureSettings, Random random) {
        List<ItemStack> items = roll(depth, treasureSettings, random);
        return items.isEmpty() ? null : items.get(0);
    }

    public List<ItemStack> roll(int depth, TreasureSettings treasureSettings, Random random) {
        ChestLootTierSettings tier = treasureSettings.tierForDepth(depth);
        if (tier.entries().isEmpty()) {
            return List.of();
        }

        int rolls = randomRollCount(tier, random);
        List<ItemStack> results = new ArrayList<>(rolls);
        for (int index = 0; index < rolls; index++) {
            ItemStack itemStack = createItem(select(tier.entries(), random), random);
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                results.add(itemStack);
            }
        }
        return results;
    }

    private int randomRollCount(ChestLootTierSettings tier, Random random) {
        int minRolls = Math.max(0, tier.minRolls());
        int maxRolls = Math.max(minRolls, tier.maxRolls());
        if (maxRolls <= minRolls) {
            return minRolls;
        }
        return minRolls + random.nextInt(maxRolls - minRolls + 1);
    }

    private ChestLootEntrySettings select(List<ChestLootEntrySettings> entries, Random random) {
        int totalWeight = 0;
        for (ChestLootEntrySettings entry : entries) {
            totalWeight += entry.weight();
        }
        int cursor = random.nextInt(totalWeight);
        for (ChestLootEntrySettings entry : entries) {
            cursor -= entry.weight();
            if (cursor < 0) {
                return entry;
            }
        }
        return entries.get(0);
    }

    private ItemStack createItem(ChestLootEntrySettings entry, Random random) {
        Material material = Material.matchMaterial(entry.material());
        if (material == null || material == Material.AIR) {
            return null;
        }

        int minAmount = Math.max(1, entry.minAmount());
        int maxAmount = Math.max(minAmount, entry.maxAmount());
        int amount = minAmount == maxAmount ? minAmount : minAmount + random.nextInt(maxAmount - minAmount + 1);
        ItemStack itemStack = new ItemStack(material, amount);
        applyMetadata(itemStack, entry);
        return itemStack;
    }

    private void applyMetadata(ItemStack itemStack, ChestLootEntrySettings entry) {
        if (itemStack.getItemMeta() instanceof PotionMeta potionMeta && entry.potionType() != null) {
            try {
                potionMeta.setBasePotionType(PotionType.valueOf(entry.potionType().toUpperCase(Locale.ROOT)));
                itemStack.setItemMeta(potionMeta);
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }

        if (itemStack.getItemMeta() instanceof EnchantmentStorageMeta storageMeta && !entry.storedEnchantments().isEmpty()) {
            for (Map.Entry<String, Integer> enchantmentEntry : entry.storedEnchantments().entrySet()) {
                Enchantment enchantment = Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft(enchantmentEntry.getKey().toLowerCase(Locale.ROOT))
                );
                if (enchantment != null) {
                    storageMeta.addStoredEnchant(enchantment, Math.max(1, enchantmentEntry.getValue()), true);
                }
            }
            itemStack.setItemMeta(storageMeta);
        }

        ItemAdventurePredicateSupport.setCanBreak(itemStack, entry.canDestroy());
        if (itemStack.getItemMeta() instanceof Damageable damageable && entry.durability() != null) {
            int maxDamage = itemStack.getType().getMaxDurability();
            int damage = Math.max(0, maxDamage - entry.durability());
            damageable.setDamage(damage);
            itemStack.setItemMeta(damageable);
        }
    }
}
