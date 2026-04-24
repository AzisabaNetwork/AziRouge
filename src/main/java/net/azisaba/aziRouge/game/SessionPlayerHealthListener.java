package net.azisaba.aziRouge.game;

import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class SessionPlayerHealthListener implements Listener {
    private final GameSessionManager sessionManager;

    public SessionPlayerHealthListener(GameSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) {
            return;
        }
        if (sessionManager.sessionForWorld(player.getWorld()).isEmpty()) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getDamageSource().getDamageType() != DamageType.STARVE) {
            return;
        }
        if (sessionManager.sessionForWorld(player.getWorld()).isEmpty()) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        sessionManager.handlePlayerDeath(event.getEntity());
    }
}
