package net.azisaba.aziRouge.listener;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.game.GameSession;
import net.azisaba.aziRouge.game.SessionState;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class GlobalJoinQuitListener implements Listener {
    private final AziRouge plugin;

    public GlobalJoinQuitListener(AziRouge plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onLoginSpawn(AsyncPlayerSpawnLocationEvent e) {
        e.setSpawnLocation(e.getSpawnLocation().getWorld().getSpawnLocation());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (plugin.settings().joinSettings().isBeta() && !e.getPlayer().hasPermission("azirouge.beta.join")) {
            e.getPlayer().kick(Component.text(plugin.messages().text("join.beta-denied", "This server is currently in closed beta. You do not have permission to join.")));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld().getName().equals("world") || e.getBlock().getWorld().getName().equals("azirouge")) {
            if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld().getName().equals("world") || e.getBlock().getWorld().getName().equals("azirouge")) {
            if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onTake(PlayerInsertLecternBookEvent e) {
        if (e.getBlock().getWorld().getName().equals("world") || e.getBlock().getWorld().getName().equals("azirouge")) {
            if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isAliveBossParticipant(p)) return;
        if (p.getWorld().getName().equals("world")) {
            e.setCancelled(true);
        }
    }

    private boolean isAliveBossParticipant(Player player) {
        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        return session != null
                && session.state() == SessionState.IN_ROUND
                && session.isBossBattleActive()
                && session.alivePlayers().contains(player.getUniqueId())
                && player.getGameMode() != GameMode.SPECTATOR;
    }

}
