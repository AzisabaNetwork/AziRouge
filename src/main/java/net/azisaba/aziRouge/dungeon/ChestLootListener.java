package net.azisaba.aziRouge.dungeon;

import net.azisaba.aziRouge.AziRouge;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public final class ChestLootListener implements Listener {
    private final AziRouge plugin;
    private final NamespacedKey chestIdKey;

    public ChestLootListener(AziRouge plugin) {
        this.plugin = plugin;
        this.chestIdKey = new NamespacedKey(plugin, ChestPopulator.LOOT_CHEST_ID_KEY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof Chest chest)) {
            return;
        }

        String rawChestId = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (rawChestId == null) {
            return;
        }

        UUID chestId;
        try {
            chestId = UUID.fromString(rawChestId);
        } catch (IllegalArgumentException ignored) {
            return;
        }

        plugin.gameSessionManager().sessionForWorld(chest.getWorld())
                .filter(session -> session.isMember(player.getUniqueId()))
                .ifPresent(session -> plugin.statisticsService()
                        .recordChestOpened(session.runId(), player, chestId));
    }
}
