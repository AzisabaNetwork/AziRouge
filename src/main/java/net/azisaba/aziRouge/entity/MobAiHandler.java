package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

public interface MobAiHandler {
    default void onSpawn(LivingEntity mob, MobAiContext context) {
    }

    default void onTick(LivingEntity mob, MobAiContext context) {
    }

    default void onTarget(LivingEntity mob, EntityTargetLivingEntityEvent event, MobAiContext context) {
    }

    default void onDamaged(LivingEntity mob, EntityDamageByEntityEvent event, MobAiContext context) {
    }

    default void onAttack(LivingEntity mob, EntityDamageByEntityEvent event, MobAiContext context) {
    }

    default void onPathFound(LivingEntity mob, EntityPathfindEvent event, MobAiContext context) {
    }
}
