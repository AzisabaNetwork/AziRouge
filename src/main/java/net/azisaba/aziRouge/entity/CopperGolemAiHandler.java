package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import net.azisaba.aziRouge.config.TorchBreakSettings;
import net.azisaba.aziRouge.config.TorchSpawnPenaltySettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CopperGolemAiHandler implements MobAiHandler {
    @Override
    public void onSpawn(LivingEntity mob, MobAiContext context) {
        mob.setInvulnerable(true);
        mob.setPersistent(true);
        if (!(mob instanceof Mob controlledMob)) {
            return;
        }

        controlledMob.setTarget(null);
        TorchBreakSettings torchBreakSettings = context.settings().ai().torchBreak();
        if (!torchBreakSettings.enabled()) {
            return;
        }

        Set<Material> torchMaterials = new LinkedHashSet<>();
        for (TorchSpawnPenaltySettings torchSettings : context.plugin().settings().azirouge().mobSpawn().light().torchTypes().values()) {
            torchMaterials.addAll(torchSettings.materials());
        }
        if (!torchMaterials.isEmpty()) {
            Bukkit.getMobGoals().addGoal(controlledMob, 5, new TorchBreakGoal(controlledMob, torchMaterials, torchBreakSettings));
        }
    }

    @Override
    public void onTarget(LivingEntity mob, EntityTargetLivingEntityEvent event, MobAiContext context) {
        event.setCancelled(true);
        if (mob instanceof Mob controlledMob) {
            controlledMob.setTarget(null);
        }
    }

    @Override
    public void onDamaged(LivingEntity mob, EntityDamageByEntityEvent event, MobAiContext context) {
        event.setCancelled(true);
        mob.setInvulnerable(true);
    }

    @Override
    public void onAttack(LivingEntity mob, EntityDamageByEntityEvent event, MobAiContext context) {
        event.setCancelled(true);
        if (mob instanceof Mob controlledMob) {
            controlledMob.setTarget(null);
        }
    }

    @Override
    public void onPathFound(LivingEntity mob, EntityPathfindEvent event, MobAiContext context) {
        if (!PathfindTargetRegistry.isAllowed(mob, event.getLoc())) {
            event.setCancelled(true);
        }
    }
}
