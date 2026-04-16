package net.azisaba.aziRouge.entity;

import org.bukkit.entity.LivingEntity;

public final class SpiderStalkerAiHandler implements MobAiHandler {
    @Override
    public void onSpawn(LivingEntity mob, MobAiContext context) {
        // TODO: Apply Bukkit/Paper-side spawn-time AI adjustments for spider stalker.
    }

    @Override
    public void onTick(LivingEntity mob, MobAiContext context) {
        // TODO: Add spider stalker-specific periodic AI logic here.
    }
}
