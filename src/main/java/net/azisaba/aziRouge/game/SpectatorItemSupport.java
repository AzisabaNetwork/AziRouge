package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class SpectatorItemSupport {
    private static final String KEY = "spectator_action";
    private static final String SWITCH_TARGET = "switch_target";

    private SpectatorItemSupport() {
    }

    public static void give(AziRouge plugin, Player player) {
        player.getInventory().setItem(4, create(plugin));
    }

    public static void remove(AziRouge plugin, Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isSwitchTargetItem(plugin, contents[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    public static boolean isSwitchTargetItem(AziRouge plugin, ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return SWITCH_TARGET.equals(container.get(key(plugin), PersistentDataType.STRING));
    }

    private static ItemStack create(AziRouge plugin) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection()
                    .deserialize(plugin.messages().text("items.switch-spectator-target", "&b観戦先を切り替え"))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(LegacyComponentSerializer.legacySection()
                    .deserialize(plugin.messages().text("items.switch-spectator-target-lore", "&7右クリックで次の生存者へ"))
                    .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, SWITCH_TARGET);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static NamespacedKey key(AziRouge plugin) {
        return new NamespacedKey(plugin, KEY);
    }
}
