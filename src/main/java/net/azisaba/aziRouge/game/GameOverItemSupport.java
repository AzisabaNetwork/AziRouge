package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class GameOverItemSupport {
    private static final String KEY = "game_over_action";
    private static final String LEAVE = "leave_session";
    private static final String MENU = "menu";

    private GameOverItemSupport() {
    }

    public static void give(AziRouge plugin, Player player) {
        player.getInventory().setItem(0, create(plugin, Material.RED_BED, plugin.messages().text("items.leave-session", "Leave Session"), LEAVE));
        player.getInventory().setItem(4, create(plugin, Material.COMPASS, plugin.messages().text("items.open-menu", "Open Menu"), MENU));
    }

    public static void remove(AziRouge plugin, Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (isLeaveItem(plugin, item) || isMenuItem(plugin, item)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    public static boolean isLeaveItem(AziRouge plugin, ItemStack item) {
        return hasAction(plugin, item, LEAVE);
    }

    public static boolean isMenuItem(AziRouge plugin, ItemStack item) {
        return hasAction(plugin, item, MENU);
    }

    private static ItemStack create(AziRouge plugin, Material material, String name, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean hasAction(AziRouge plugin, ItemStack item, String action) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return action.equals(container.get(key(plugin), PersistentDataType.STRING));
    }

    private static NamespacedKey key(AziRouge plugin) {
        return new NamespacedKey(plugin, KEY);
    }
}
