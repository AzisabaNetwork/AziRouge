package net.azisaba.aziRouge.entity;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public enum MobProfile {
    ZOMBIE_BRUTE("zombie_brute", EntityType.ZOMBIE, 12, 40.0D, 0.30D, 6.0D),
    SKELETON_ARCHER("skeleton_archer", EntityType.SKELETON, 10, 32.0D, 0.28D, 5.0D),
    SPIDER_STALKER("spider_stalker", EntityType.SPIDER, 9, 28.0D, 0.38D, 4.5D);

    public static final String MOB_TAG = "azirouge_mob";
    private static final String PROFILE_TAG_PREFIX = "azirouge_profile:";

    private final String key;
    private final EntityType entityType;
    private final int weight;
    private final double maxHealth;
    private final double movementSpeed;
    private final double attackDamage;

    MobProfile(String key, EntityType entityType, int weight, double maxHealth, double movementSpeed, double attackDamage) {
        this.key = key;
        this.entityType = entityType;
        this.weight = weight;
        this.maxHealth = maxHealth;
        this.movementSpeed = movementSpeed;
        this.attackDamage = attackDamage;
    }

    public EntityType entityType() {
        return entityType;
    }

    public void apply(LivingEntity mob) {
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.addScoreboardTag(MOB_TAG);
        mob.addScoreboardTag(PROFILE_TAG_PREFIX + key);
        applyAttribute(mob, Attribute.MAX_HEALTH, maxHealth);
        applyAttribute(mob, Attribute.MOVEMENT_SPEED, movementSpeed);
        applyAttribute(mob, Attribute.ATTACK_DAMAGE, attackDamage);
        mob.setHealth(Math.min(maxHealth, mob.getMaxHealth()));
    }

    public List<ItemStack> createDrops(Random random, int depth) {
        return switch (this) {
            case ZOMBIE_BRUTE -> zombieDrops(random, depth);
            case SKELETON_ARCHER -> skeletonDrops(random, depth);
            case SPIDER_STALKER -> spiderDrops(random, depth);
        };
    }

    public static MobProfile random(Random random) {
        int totalWeight = 0;
        for (MobProfile profile : values()) {
            totalWeight += profile.weight;
        }
        int cursor = random.nextInt(totalWeight);
        for (MobProfile profile : values()) {
            cursor -= profile.weight;
            if (cursor < 0) {
                return profile;
            }
        }
        return values()[0];
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

    private List<ItemStack> zombieDrops(Random random, int depth) {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(Material.ROTTEN_FLESH, 1 + random.nextInt(3)));
        if (random.nextDouble() < 0.15D + depth * 0.03D) {
            drops.add(new ItemStack(Material.IRON_INGOT, 1));
        }
        if (depth >= 6 && random.nextDouble() < 0.12D) {
            drops.add(new ItemStack(Material.EMERALD, 1));
        }
        return drops;
    }

    private List<ItemStack> skeletonDrops(Random random, int depth) {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(3)));
        drops.add(new ItemStack(Material.ARROW, 2 + random.nextInt(5)));
        if (random.nextDouble() < 0.10D + depth * 0.02D) {
            drops.add(new ItemStack(Material.BOW, 1));
        }
        if (depth >= 5 && random.nextDouble() < 0.10D) {
            drops.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 1));
        }
        return drops;
    }

    private List<ItemStack> spiderDrops(Random random, int depth) {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(Material.STRING, 1 + random.nextInt(4)));
        if (random.nextDouble() < 0.40D) {
            drops.add(new ItemStack(Material.SPIDER_EYE, 1));
        }
        if (depth >= 3 && random.nextDouble() < 0.10D + depth * 0.02D) {
            drops.add(new ItemStack(Material.FERMENTED_SPIDER_EYE, 1));
        }
        return drops;
    }
}
