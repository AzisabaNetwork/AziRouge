package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.game.PlayerInventorySupport;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public final class TreasurePickupListener implements Listener {
    private final AziRouge plugin;

    public TreasurePickupListener(AziRouge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Interaction interaction)) {
            return;
        }
        if (!interaction.getScoreboardTags().contains(DisplayGimmickKeys.TREASURE_INTERACTION_TAG)) {
            return;
        }

        event.setCancelled(true);
        String displayId = interaction.getPersistentDataContainer().get(
                DisplayGimmickKeys.linkedDisplayKey(plugin),
                PersistentDataType.STRING
        );
        if (displayId == null) {
            interaction.remove();
            return;
        }

        Entity entity;
        try {
            entity = Bukkit.getEntity(UUID.fromString(displayId));
        } catch (IllegalArgumentException ex) {
            interaction.remove();
            return;
        }

        if (!(entity instanceof ItemDisplay display)) {
            interaction.remove();
            return;
        }

        ItemStack reward = display.getItemStack();
        if (reward == null || reward.getType() == Material.AIR) {
            display.remove();
            interaction.remove();
            return;
        }

        if (!PlayerInventorySupport.canFitHotbar(event.getPlayer().getInventory(), reward)) {
            event.getPlayer().sendMessage(plugin.messages().text("treasure.hotbar-full", "&cYour hotbar is full. Clear a hotbar slot before picking up treasure."));
            return;
        }
        PlayerInventorySupport.addToHotbar(event.getPlayer().getInventory(), reward.clone());

        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8F, 1.1F);
        display.remove();
        interaction.remove();
    }
}
