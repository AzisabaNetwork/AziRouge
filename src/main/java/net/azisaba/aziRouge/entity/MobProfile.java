package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.MobAiSettings;
import net.azisaba.aziRouge.config.MobDropEntrySettings;
import net.azisaba.aziRouge.config.MobProfileSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;

public enum MobProfile {
    ZOMBIE_BRUTE("zombie_brute", EntityType.ZOMBIE, 12, 12, 20.0D, 0.40D, 5.0D),
    SKELETON_ARCHER("skeleton_archer", EntityType.SKELETON, 10, 10, 32.0D, 0.28D, 5.0D),
    SPIDER_STALKER("spider_stalker", EntityType.SPIDER, 9, 8, 28.0D, 0.38D, 4.5D);

    public static final String MOB_TAG = "azirouge_mob";
    private static final String PROFILE_TAG_PREFIX = "azirouge_profile:";

    private final String key;
    private final EntityType entityType;
    private final int weight;
    private final int power;
    private final double maxHealth;
    private final double movementSpeed;
    private final double attackDamage;
    private final MobProfileSettings defaultSettings;

    MobProfile(String key, EntityType entityType, int weight, int power, double maxHealth, double movementSpeed, double attackDamage) {
        this.key = key;
        this.entityType = entityType;
        this.weight = weight;
        this.power = power;
        this.maxHealth = maxHealth;
        this.movementSpeed = movementSpeed;
        this.attackDamage = attackDamage;
        this.defaultSettings = new MobProfileSettings(
                weight,
                power,
                maxHealth,
                movementSpeed,
                attackDamage,
                new MobAiSettings(true, 20L),
                defaultDropsFor(key)
        );
    }

    public String key() {
        return key;
    }

    public MobProfileSettings defaultSettings() {
        return defaultSettings;
    }

    public EntityType entityType() {
        return entityType;
    }

    public void apply(LivingEntity mob) {
        apply(mob, defaultSettings);
    }

    public void apply(LivingEntity mob, MobProfileSettings settings) {
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.addScoreboardTag(MOB_TAG);
        mob.addScoreboardTag(PROFILE_TAG_PREFIX + key);
        applyAttribute(mob, Attribute.MAX_HEALTH, settings.maxHealth());
        applyAttribute(mob, Attribute.MOVEMENT_SPEED, settings.movementSpeed());
        applyAttribute(mob, Attribute.ATTACK_DAMAGE, settings.attackDamage());
        applyAttribute(mob, Attribute.KNOCKBACK_RESISTANCE, 0.8D);
        mob.setHealth(Math.min(settings.maxHealth(), mob.getMaxHealth()));
        if (mob instanceof Mob m) {
            m.getPathfinder().setCanOpenDoors(true);
            Bukkit.getMobGoals().removeGoal(m, VanillaGoal.RANDOM_LOOK_AROUND);
            Bukkit.getMobGoals().removeGoal(m, VanillaGoal.LOOK_AT_PLAYER);
            if (m instanceof Creature creature) {
                Bukkit.getMobGoals().removeGoal(creature, VanillaGoal.RANDOM_STROLL);
                Bukkit.getMobGoals().removeGoal(creature, VanillaGoal.WATER_AVOIDING_RANDOM_STROLL);
            }
            Bukkit.getMobGoals().addGoal(m, 3, new DoorOpenGoal(m));
        }
    }

    public List<ItemStack> createDrops(Random random, int depth) {
        return createDrops(random, depth, defaultSettings);
    }

    public List<ItemStack> createDrops(Random random, int depth, MobProfileSettings settings) {
        List<ItemStack> drops = new ArrayList<>();
        for (MobDropEntrySettings entry : settings.drops()) {
            if (!entry.matchesDepth(depth)) {
                continue;
            }
            if (random.nextDouble() > entry.chanceAtDepth(depth)) {
                continue;
            }

            ItemStack itemStack = createDropItem(entry, random);
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                drops.add(itemStack);
            }
        }
        return drops;
    }

    public static MobProfile fromEntity(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (!tag.startsWith(PROFILE_TAG_PREFIX)) {
                continue;
            }
            String tagKey = tag.substring(PROFILE_TAG_PREFIX.length());
            for (MobProfile profile : values()) {
                if (profile.key.equalsIgnoreCase(tagKey)) {
                    return profile;
                }
            }
        }
        for (MobProfile profile : values()) {
            if (profile.entityType == entity.getType()) {
                return profile;
            }
        }
        return null;
    }

    private static void applyAttribute(LivingEntity mob, Attribute attribute, double value) {
        AttributeInstance attributeInstance = mob.getAttribute(attribute);
        if (attributeInstance != null) {
            attributeInstance.setBaseValue(value);
        }
    }

    private ItemStack createDropItem(MobDropEntrySettings entry, Random random) {
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

    private void applyMetadata(ItemStack itemStack, MobDropEntrySettings entry) {
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
    }

    private static List<MobDropEntrySettings> defaultDropsFor(String key) {
        return switch (key) {
            case "zombie_brute" -> List.of(
                    new MobDropEntrySettings("ROTTEN_FLESH", 1.0D, 0.0D, 1, 3, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("IRON_INGOT", 0.15D, 0.03D, 1, 1, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("EMERALD", 0.12D, 0.0D, 1, 1, 6, Integer.MAX_VALUE, null, Map.of())
            );
            case "skeleton_archer" -> List.of(
                    new MobDropEntrySettings("BONE", 1.0D, 0.0D, 1, 3, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("ARROW", 1.0D, 0.0D, 2, 6, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("BOW", 0.10D, 0.02D, 1, 1, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("EXPERIENCE_BOTTLE", 0.10D, 0.0D, 1, 1, 5, Integer.MAX_VALUE, null, Map.of())
            );
            case "spider_stalker" -> List.of(
                    new MobDropEntrySettings("STRING", 1.0D, 0.0D, 1, 4, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("SPIDER_EYE", 0.40D, 0.0D, 1, 1, 0, Integer.MAX_VALUE, null, Map.of()),
                    new MobDropEntrySettings("FERMENTED_SPIDER_EYE", 0.10D, 0.02D, 1, 1, 3, Integer.MAX_VALUE, null, Map.of())
            );
            default -> List.of();
        };
    }
}
