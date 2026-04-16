package net.azisaba.aziRouge.entity;

import org.bukkit.entity.LivingEntity;

public final class ZombieBruteAiHandler implements MobAiHandler {
    @Override
    public void onSpawn(LivingEntity mob, MobAiContext context) {
        // TODO: Apply Bukkit/Paper-side spawn-time AI adjustments for zombie brute.
    }

    @Override
    public void onTick(LivingEntity mob, MobAiContext context) {
        // TODO: Add zombie brute-specific periodic AI logic here.
    }
}
