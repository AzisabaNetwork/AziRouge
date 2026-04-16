package net.azisaba.aziRouge.entity;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.game.GameSessionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class MobDropListener implements Listener {
    private final AziRouge plugin;
    private final GameSessionManager sessionManager;

    public MobDropListener(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(MobProfile.MOB_TAG)) {
            return;
        }

        MobProfile profile = MobProfile.fromEntity(event.getEntity());
        if (profile == null) {
            return;
        }

        int depth = sessionManager.resolveDepth(event.getEntity().getWorld(), event.getEntity().getLocation());
        Random random = ThreadLocalRandom.current();
        event.getDrops().clear();
        event.getDrops().addAll(profile.createDrops(
                random,
                depth,
                plugin.settings().azirouge().mobSpawn().profile(profile)
        ));
        event.setDroppedExp(Math.max(1, 3 + depth));
    }
}
