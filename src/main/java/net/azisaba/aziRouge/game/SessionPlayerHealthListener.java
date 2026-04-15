package net.azisaba.aziRouge.game;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;

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
}
