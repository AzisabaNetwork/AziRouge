package net.azisaba.aziRouge.config;

import org.bukkit.Material;

public record ShopTradeSettings(
        String id,
        Material material,
        int amount,
        long price
) {
}
