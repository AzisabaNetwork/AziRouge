package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.TrapDefinitionSettings;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

public final class TrapTriggerListener implements Listener {
    private final AziRouge plugin;

    public TrapTriggerListener(AziRouge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (event.getFrom().toVector().distanceSquared(event.getTo().toVector()) < 1.0E-6D) {
            return;
        }

        for (Entity entity : event.getTo().getWorld().getNearbyEntities(event.getTo(), 1.75D, 1.5D, 1.75D)) {
            if (!(entity instanceof BlockDisplay display)) {
                continue;
            }
            if (!display.getScoreboardTags().contains(DisplayGimmickKeys.TRAP_DISPLAY_TAG)) {
                continue;
            }

            String trapKey = display.getPersistentDataContainer().get(
                    DisplayGimmickKeys.trapKey(plugin),
                    PersistentDataType.STRING
            );
            if (trapKey == null) {
                display.remove();
                continue;
            }

            TrapDefinitionSettings definition = plugin.settings().azirouge().traps().definition(trapKey);
            if (definition == null) {
                display.remove();
                continue;
            }

            Location trapCenter = display.getLocation().clone().add(0.5D, 0.1D, 0.5D);
            if (trapCenter.distanceSquared(event.getTo()) > definition.triggerRadius() * definition.triggerRadius()) {
                continue;
            }

            display.remove();
            event.getPlayer().getWorld().createExplosion(
                    trapCenter.getX(),
                    trapCenter.getY(),
                    trapCenter.getZ(),
                    definition.explosionPower(),
                    definition.setFire(),
                    definition.breakBlocks(),
                    null
            );
            break;
        }
    }
}
