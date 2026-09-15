package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.MobProfileSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class MobAiManager implements Listener {
    private final AziRouge plugin;
    private final CopperGolemAiHandler copperGolem = new CopperGolemAiHandler();
    private final Map<UUID, MobProfile> trackedMobs = new HashMap<>();
    private final BukkitTask tickTask;

    public MobAiManager(AziRouge plugin) {
        this.plugin = plugin;
        this.tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupTrackedMobs();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void track(LivingEntity mob, MobProfile profile) {
        trackedMobs.put(mob.getUniqueId(), profile);
        runSpawnHook(mob, profile);
    }

    public void shutdown() {
        tickTask.cancel();
        trackedMobs.clear();
        PathfindTargetRegistry.clearAll();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }
        dispatchCopper(livingEntity, (handler, context) -> handler.onTarget(livingEntity, event, context));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity targetMob)) {
            return;
        }
        if (resolveProfile(targetMob) != MobProfile.COPPER_GOLEM) {
            return;
        }
        event.setCancelled(true);
        targetMob.setInvulnerable(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof LivingEntity targetMob) {
            dispatchCopper(targetMob, (handler, context) -> handler.onDamaged(targetMob, event, context));
        }
        if (event.getDamager() instanceof LivingEntity attackingMob) {
            dispatchCopper(attackingMob, (handler, context) -> handler.onAttack(attackingMob, event, context));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPathfind(EntityPathfindEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }
        dispatchCopper(livingEntity, (handler, context) -> handler.onPathFound(livingEntity, event, context));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        trackedMobs.remove(event.getEntity().getUniqueId());
        PathfindTargetRegistry.clear(event.getEntity());
    }

    private void runSpawnHook(LivingEntity mob, MobProfile profile) {
        dispatchCopper(mob, profile, (handler, context) -> handler.onSpawn(mob, context));
    }

    private void cleanupTrackedMobs() {
        Iterator<Map.Entry<UUID, MobProfile>> iterator = trackedMobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MobProfile> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity) || !entity.isValid() || entity.isDead()
                    || !entity.getScoreboardTags().contains(MobProfile.MOB_TAG)) {
                PathfindTargetRegistry.clear(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void dispatchCopper(
            LivingEntity mob,
            BiConsumer<CopperGolemAiHandler, MobAiContext> action
    ) {
        dispatchCopper(mob, resolveProfile(mob), action);
    }

    private void dispatchCopper(
            LivingEntity mob,
            MobProfile profile,
            BiConsumer<CopperGolemAiHandler, MobAiContext> action
    ) {
        if (profile != MobProfile.COPPER_GOLEM) {
            return;
        }
        MobProfileSettings settings = plugin.settings().azirouge().mobSpawn().profile(profile);
        if (!settings.ai().enabled()) {
            return;
        }
        action.accept(copperGolem, new MobAiContext(plugin, profile, settings));
    }

    private MobProfile resolveProfile(LivingEntity livingEntity) {
        MobProfile profile = trackedMobs.get(livingEntity.getUniqueId());
        if (profile != null) {
            return profile;
        }
        if (!livingEntity.getScoreboardTags().contains(MobProfile.MOB_TAG)) {
            return null;
        }
        profile = MobProfile.fromEntity(livingEntity);
        if (profile != null) {
            trackedMobs.put(livingEntity.getUniqueId(), profile);
        }
        return profile;
    }
}
