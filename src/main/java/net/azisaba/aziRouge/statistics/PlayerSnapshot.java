package net.azisaba.aziRouge.statistics;

import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

public record PlayerSnapshot(UUID playerId, String playerName) {
    public PlayerSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        playerName = sanitizeName(playerName);
    }

    public static PlayerSnapshot from(Player player) {
        Objects.requireNonNull(player, "player");
        return new PlayerSnapshot(player.getUniqueId(), player.getName());
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "Unknown";
        }
        String trimmed = name.trim();
        return trimmed.length() <= 16 ? trimmed : trimmed.substring(0, 16);
    }
}
