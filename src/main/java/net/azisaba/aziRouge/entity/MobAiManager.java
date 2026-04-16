package net.azisaba.aziRouge.entity;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.MobAiSettings;
import net.azisaba.aziRouge.config.MobProfileSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class MobAiManager implements Listener {
    private final AziRouge plugin;
    private final Map<MobProfile, MobAiHandler> handlers;
    private final Map<UUID, MobProfile> trackedMobs = new HashMap<>();
    private final BukkitTask tickTask;
    private long tickCounter;

    public MobAiManager(AziRouge plugin) {
        this.plugin = plugin;
        this.handlers = Map.of(
                MobProfile.ZOMBIE_BRUTE, new ZombieBruteAiHandler(),
                MobProfile.SKELETON_ARCHER, new SkeletonArcherAiHandler(),
                MobProfile.SPIDER_STALKER, new SpiderStalkerAiHandler()
        );
        this.tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickTrackedMobs();
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
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }

        MobProfile profile = resolveProfile(livingEntity);
        if (profile == null) {
            return;
        }

        MobProfileSettings settings = plugin.settings().azirouge().mobSpawn().profile(profile);
        if (!settings.ai().enabled()) {
            return;
        }
        handler(profile).onTarget(livingEntity, event, new MobAiContext(plugin, profile, settings));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof LivingEntity targetMob) {
            MobProfile targetProfile = resolveProfile(targetMob);
            if (targetProfile != null) {
                MobProfileSettings settings = plugin.settings().azirouge().mobSpawn().profile(targetProfile);
                if (settings.ai().enabled()) {
                    handler(targetProfile).onDamaged(targetMob, event, new MobAiContext(plugin, targetProfile, settings));
                }
            }
        }

        if (event.getDamager() instanceof LivingEntity attackingMob) {
            MobProfile attackerProfile = resolveProfile(attackingMob);
            if (attackerProfile != null) {
                MobProfileSettings settings = plugin.settings().azirouge().mobSpawn().profile(attackerProfile);
                if (settings.ai().enabled()) {
                    handler(attackerProfile).onAttack(attackingMob, event, new MobAiContext(plugin, attackerProfile, settings));
                }
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        trackedMobs.remove(event.getEntity().getUniqueId());
    }

    private void runSpawnHook(LivingEntity mob, MobProfile profile) {
        MobProfileSettings settings = plugin.settings().azirouge().mobSpawn().profile(profile);
        if (!settings.ai().enabled()) {
            return;
        }
        handler(profile).onSpawn(mob, new MobAiContext(plugin, profile, settings));
    }

    private void tickTrackedMobs() {
        tickCounter++;
        Iterator<Map.Entry<UUID, MobProfile>> iterator = trackedMobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MobProfile> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity livingEntity) || !entity.isValid() || entity.isDead()) {
                iterator.remove();
                continue;
            }
            if (!livingEntity.getScoreboardTags().contains(MobProfile.MOB_TAG)) {
                iterator.remove();
                continue;
            }

            MobProfileSettings settings = plugin.settings().azirouge().mobSpawn().profile(entry.getValue());
            MobAiSettings aiSettings = settings.ai();
            if (!aiSettings.enabled()) {
                continue;
            }

            long interval = Math.max(1L, aiSettings.tickIntervalTicks());
            if (tickCounter % interval != 0L) {
                continue;
            }
            handler(entry.getValue()).onTick(livingEntity, new MobAiContext(plugin, entry.getValue(), settings));
        }
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

    private MobAiHandler handler(MobProfile profile) {
        return handlers.getOrDefault(profile, new MobAiHandler() {
        });
    }
}
