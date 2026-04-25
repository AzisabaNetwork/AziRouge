package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.EconomyMaintenanceSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.UUID;

public final class EconomyService {
    private final AziRouge plugin;

    public EconomyService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public long maintenanceCostForRound(int roundNumber) {
        EconomyMaintenanceSettings maintenance = plugin.settings().economy().maintenance();
        double rawCost = (maintenance.base() + maintenance.perRound() * (double) Math.max(1, roundNumber))
                * maintenance.multiplier();
        if (rawCost <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil(rawCost);
    }

    public long chargeMaintenanceOrGameOver(GameSession session) {
        long cost = maintenanceCostForRound(session.currentRound() + 1);
        if (session.withdrawSharedBalance(cost)) {
            return cost;
        }

        session.clearRoundPlayers();
        session.setRoundState(RoundState.ENDED);
        session.setState(SessionState.GAME_OVER);
        throw new IllegalStateException("Session " + session.sessionId()
                + " cannot pay round " + (session.currentRound() + 1)
                + " maintenance cost " + cost
                + " (balance=" + session.sharedBalance() + "). Game over.");
    }

    public SellResult sellInventoryLoot(GameSession session) {
        Map<Material, Long> prices = plugin.settings().economy().sellPrices();
        if (prices.isEmpty()) {
            return new SellResult(0L, 0);
        }

        long totalAmount = 0L;
        int totalItems = 0;
        for (UUID playerId : session.onlineMembers()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            SellResult playerResult = sellStorageInventory(player, prices);
            totalAmount += playerResult.totalAmount();
            totalItems += playerResult.itemCount();
        }

        if (totalAmount > 0L) {
            session.addSharedBalance(totalAmount);
        }
        return new SellResult(totalAmount, totalItems);
    }

    private SellResult sellStorageInventory(Player player, Map<Material, Long> prices) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        long totalAmount = 0L;
        int totalItems = 0;
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (item == null || item.getType().isAir()) {
                continue;
            }

            Long unitPrice = prices.get(item.getType());
            if (unitPrice == null) {
                continue;
            }

            int amount = item.getAmount();
            totalAmount += unitPrice * amount;
            totalItems += amount;
            contents[index] = null;
        }
        inventory.setStorageContents(contents);
        return new SellResult(totalAmount, totalItems);
    }

    public record SellResult(long totalAmount, int itemCount) {
    }
}
