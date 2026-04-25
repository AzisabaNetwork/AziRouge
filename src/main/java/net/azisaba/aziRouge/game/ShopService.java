package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.ShopTradeSettings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class ShopService implements Listener {
    private static final String PREFIX = ChatColor.GOLD + "[Azirouge] " + ChatColor.RESET;

    private final AziRouge plugin;
    private final GameSessionManager sessionManager;

    public ShopService(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager)) {
            return;
        }

        GameSession session = sessionManager.sessionForWorld(event.getRightClicked().getWorld()).orElse(null);
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        openShop(event.getPlayer(), session);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        ShopTradeSettings trade = holder.tradeAt(rawSlot);
        if (trade == null) {
            return;
        }

        GameSession session = sessionManager.sessionById(holder.sessionId()).orElse(null);
        if (session == null || !canUseShop(player, session)) {
            player.closeInventory();
            player.sendMessage(PREFIX + ChatColor.RED + "Shop is not available right now.");
            return;
        }

        if (session.sharedBalance() < trade.price()) {
            player.sendMessage(PREFIX + ChatColor.RED + "Not enough shared money. price=" + trade.price()
                    + " balance=" + session.sharedBalance());
            return;
        }

        ItemStack purchased = new ItemStack(trade.material(), trade.amount());
        if (!canFit(player.getInventory(), purchased)) {
            player.sendMessage(PREFIX + ChatColor.RED + "Your inventory is full.");
            return;
        }

        if (!session.withdrawSharedBalance(trade.price())) {
            player.sendMessage(PREFIX + ChatColor.RED + "Not enough shared money.");
            return;
        }
        player.getInventory().addItem(purchased);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Bought " + trade.amount() + "x " + trade.material().name()
                + " for " + trade.price() + ". balance=" + session.sharedBalance());
        refreshShop(event.getView().getTopInventory(), holder, session);
    }

    private void openShop(Player player, GameSession session) {
        if (!canUseShop(player, session)) {
            player.sendMessage(PREFIX + ChatColor.RED + "Shop is only available during rounds and between rounds.");
            return;
        }

        List<ShopTradeSettings> trades = tradesFor(session);
        if (trades.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.RED + "No shop trades are configured for " + session.state() + ".");
            return;
        }

        int size = Math.min(54, Math.max(9, ((trades.size() + 8) / 9) * 9));
        ShopHolder holder = new ShopHolder(session.sessionId(), trades, size);
        Inventory inventory = Bukkit.createInventory(
                holder,
                size,
                ChatColor.DARK_GREEN + plugin.settings().shop().title() + ChatColor.GRAY + " $" + session.sharedBalance()
        );
        holder.setInventory(inventory);
        refreshShop(inventory, holder, session);
        player.openInventory(inventory);
    }

    private void refreshShop(Inventory inventory, ShopHolder holder, GameSession session) {
        inventory.clear();
        List<ShopTradeSettings> trades = holder.trades();
        for (int slot = 0; slot < trades.size() && slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, displayItem(trades.get(slot), session));
        }
    }

    private ItemStack displayItem(ShopTradeSettings trade, GameSession session) {
        ItemStack item = new ItemStack(trade.material(), trade.amount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + trade.material().name());
            meta.setLore(List.of(
                    ChatColor.YELLOW + "Price: " + trade.price(),
                    ChatColor.GRAY + "Shared money: " + session.sharedBalance()
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<ShopTradeSettings> tradesFor(GameSession session) {
        if (session.state() == SessionState.IN_ROUND) {
            return plugin.settings().shop().inRoundTrades();
        }
        if (session.state() == SessionState.BETWEEN_ROUNDS) {
            return plugin.settings().shop().betweenRoundTrades();
        }
        return List.of();
    }

    private boolean canUseShop(Player player, GameSession session) {
        if (!player.getWorld().getUID().equals(session.world().getUID()) || !session.isMember(player.getUniqueId())) {
            return false;
        }
        if (session.state() == SessionState.BETWEEN_ROUNDS) {
            return true;
        }
        return session.state() == SessionState.IN_ROUND
                && session.alivePlayers().contains(player.getUniqueId())
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean canFit(PlayerInventory inventory, ItemStack candidate) {
        int remaining = candidate.getAmount();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                return true;
            }
            if (item.isSimilar(candidate)) {
                remaining -= Math.max(0, item.getMaxStackSize() - item.getAmount());
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class ShopHolder implements InventoryHolder {
        private final String sessionId;
        private final List<ShopTradeSettings> trades;
        private Inventory inventory;

        private ShopHolder(String sessionId, List<ShopTradeSettings> trades, int size) {
            this.sessionId = sessionId;
            this.trades = new ArrayList<>(trades.subList(0, Math.min(size, trades.size())));
        }

        private String sessionId() {
            return sessionId;
        }

        private List<ShopTradeSettings> trades() {
            return trades;
        }

        private ShopTradeSettings tradeAt(int slot) {
            if (slot < 0 || slot >= trades.size()) {
                return null;
            }
            return trades.get(slot);
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
