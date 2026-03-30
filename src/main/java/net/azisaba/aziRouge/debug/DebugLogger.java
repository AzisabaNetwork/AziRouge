package net.azisaba.aziRouge.debug;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.TreeMap;

public final class DebugLogger {
    private final JavaPlugin plugin;
    private volatile boolean enabled;

    public DebugLogger(JavaPlugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getLogger().info("debug|state_changed|enabled=" + enabled);
    }

    public void log(String scope, String event, Map<String, ?> fields) {
        if (!enabled) {
            return;
        }
        TreeMap<String, Object> sorted = new TreeMap<>(fields);
        StringBuilder builder = new StringBuilder("debug|scope=").append(scope).append("|event=").append(event);
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            builder.append("|").append(entry.getKey()).append("=").append(entry.getValue());
        }
        plugin.getLogger().info(builder.toString());
    }
}
