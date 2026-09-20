package net.azisaba.aziRouge.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MessageService {
    private final FileConfiguration messages;
    private final FileConfiguration bundledMessages;

    public MessageService(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        this.messages = YamlConfiguration.loadConfiguration(file);
        this.bundledMessages = loadBundledMessages(plugin);
    }

    public String text(String key, String fallback) {
        String configured = messages.getString(key);
        String bundled = bundledMessages.getString(key);
        if (configured == null || bundled != null
                && usesLegacyRoundLabel(configured) && !usesLegacyRoundLabel(bundled)) {
            configured = bundled;
        }
        return color(configured == null ? fallback : configured);
    }

    public String format(String key, String fallback, Object... replacements) {
        String value = text(key, fallback);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
        }
        return value;
    }

    public String prefix() {
        return text("prefix", "&6[AziRouge] &r");
    }

    public String prefixed(String key, String fallback, Object... replacements) {
        return prefix() + format(key, fallback, replacements);
    }

    public Component component(String key, String fallback, Object... replacements) {
        return LegacyComponentSerializer.legacySection().deserialize(format(key, fallback, replacements));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private boolean usesLegacyRoundLabel(String value) {
        return value != null && (value.contains("ラウンド") || value.toLowerCase(Locale.ROOT).contains("round"));
    }

    private FileConfiguration loadBundledMessages(JavaPlugin plugin) {
        InputStream stream = plugin.getResource("messages.yml");
        if (stream == null) {
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to load bundled messages.yml: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }
}
