package net.azisaba.aziRouge.listener;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.azisaba.aziRouge.AziRouge;
import net.kyori.adventure.text.Component;
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
            e.getPlayer().kick(Component.text("This server is currently in closed beta. You do not have permission to join."));
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
        if (p.getWorld().getName().equals("world")) {
            e.setCancelled(true);
        }
    }

}
