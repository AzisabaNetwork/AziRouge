package net.azisaba.aziRouge.game;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class SessionPlayerListener implements Listener {
    private final GameSessionManager sessionManager;

    public SessionPlayerListener(GameSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }

        World destinationWorld = event.getTo().getWorld();
        World sourceWorld = event.getFrom().getWorld();
        if (sourceWorld != null && sourceWorld.getUID().equals(destinationWorld.getUID())) {
            return;
        }

        sessionManager.capturePlayerJoin(event.getPlayer(), event.getFrom(), destinationWorld);
    }
}
