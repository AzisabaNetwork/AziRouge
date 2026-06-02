package net.azisaba.aziRouge.message;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class MessageService {
    private final FileConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        this.messages = YamlConfiguration.loadConfiguration(file);
    }

    public String text(String key, String fallback) {
        return color(messages.getString(key, fallback));
    }

    public String format(String key, String fallback, Object... replacements) {
        String value = text(key, fallback);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
        }
        return value;
    }

    public String prefix() {
        return text("prefix", "&6[Azirouge] &r");
    }

    public String prefixed(String key, String fallback, Object... replacements) {
        return prefix() + format(key, fallback, replacements);
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
