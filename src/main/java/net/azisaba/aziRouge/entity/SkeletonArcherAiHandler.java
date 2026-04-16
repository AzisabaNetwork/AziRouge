package net.azisaba.aziRouge.entity;

import org.bukkit.entity.LivingEntity;

public final class SkeletonArcherAiHandler implements MobAiHandler {
    @Override
    public void onSpawn(LivingEntity mob, MobAiContext context) {
        // TODO: Apply Bukkit/Paper-side spawn-time AI adjustments for skeleton archer.
    }

    @Override
    public void onTick(LivingEntity mob, MobAiContext context) {
        // TODO: Add skeleton archer-specific periodic AI logic here.
    }
}
