package net.azisaba.aziRouge.dungeon;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class DisplayGimmickKeys {
    public static final String TREASURE_DISPLAY_TAG = "azirouge_treasure_display";
    public static final String TREASURE_INTERACTION_TAG = "azirouge_treasure_interaction";
    public static final String TRAP_DISPLAY_TAG = "azirouge_trap_display";

    private DisplayGimmickKeys() {
    }

    public static NamespacedKey linkedDisplayKey(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "linked_display_uuid");
    }

    public static NamespacedKey trapKey(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "trap_definition_key");
    }
}
