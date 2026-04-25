package net.azisaba.aziRouge.config;

import java.util.List;

public record ShopSettings(
        String title,
        List<ShopTradeSettings> inRoundTrades,
        List<ShopTradeSettings> betweenRoundTrades
) {
}
