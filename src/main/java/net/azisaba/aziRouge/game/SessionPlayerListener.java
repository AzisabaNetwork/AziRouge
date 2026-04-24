package net.azisaba.aziRouge.game;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
        if (!sessionManager.isSessionWorldEntryAllowed(event.getPlayer(), destinationWorld)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("You are not a member of that AziRouge session.");
            return;
        }
        if (sourceWorld != null && sourceWorld.getUID().equals(destinationWorld.getUID())) {
            return;
        }

        sessionManager.handlePlayerWorldChange(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (event.getRespawnLocation().getWorld() == null) {
            return;
        }

        if (!sessionManager.isSessionWorldEntryAllowed(event.getPlayer(), event.getRespawnLocation().getWorld())) {
            GameSession session = sessionManager.sessionForWorld(event.getRespawnLocation().getWorld()).orElse(null);
            if (session != null) {
                org.bukkit.Location fallback = sessionManager.fallbackLocation(session);
                if (fallback != null) {
                    event.setRespawnLocation(fallback);
                }
            }
        }
        sessionManager.handlePlayerWorldChange(event.getPlayer(), event.getPlayer().getLocation(), event.getRespawnLocation());
        sessionManager.handlePlayerRespawn(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sessionManager.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionManager.handlePlayerQuit(event.getPlayer());
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        e.setCancelled(true);
    }
}
