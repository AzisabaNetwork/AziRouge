package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.ShopTradeSettings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class ShopService implements Listener {
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

        Inventory inventory = event.getView().getTopInventory();
        plugin.confirmationService().request(
                player,
                plugin.messages().format(
                        "shop.confirm-purchase",
                        "Purchase {item} x{amount} for {price} shared money?",
                        "item",
                        trade.material().name(),
                        "amount",
                        trade.amount(),
                        "price",
                        trade.price()
                ),
                () -> handlePurchase(player, holder, trade, inventory)
        );
    }

    private void handlePurchase(Player player, ShopHolder holder, ShopTradeSettings trade, Inventory inventory) {
        GameSession session = sessionManager.sessionById(holder.sessionId()).orElse(null);
        if (session == null || !canUseShop(player, session)) {
            player.closeInventory();
            player.sendMessage(plugin.messages().prefix() + m("shop.unavailable", "&cショップは現在利用できません。"));
            return;
        }

        if (session.sharedBalance() < trade.price()) {
            player.sendMessage(plugin.messages().prefix() + m("shop.not-enough-money-detail", "&c共有資金が足りません。価格 {price} / 残高 {balance}", "price", trade.price(), "balance", session.sharedBalance()));
            return;
        }

        ItemStack purchased = tradeItem(trade);
        if (!PlayerInventorySupport.canFit(player.getInventory(), purchased)) {
            player.sendMessage(plugin.messages().prefix() + m("shop.inventory-full", "&cインベントリに空きがありません。"));
            return;
        }

        if (!session.withdrawSharedBalance(trade.price())) {
            player.sendMessage(plugin.messages().prefix() + m("shop.not-enough-money", "&c共有資金が足りません。"));
            return;
        }
        player.getInventory().addItem(purchased);
        player.sendMessage(plugin.messages().prefix() + m("shop.purchased", "&a{item} x{amount} を購入しました。価格 {price} / 残高 {balance}", "item", trade.material().name(), "amount", trade.amount(), "price", trade.price(), "balance", session.sharedBalance()));
        refreshShop(inventory, holder, session);
    }

    private void openShop(Player player, GameSession session) {
        if (!canUseShop(player, session)) {
            player.sendMessage(plugin.messages().prefix() + m("shop.only-during-rounds", "&cショップはラウンド中またはラウンド間だけ使えます。"));
            return;
        }

        List<ShopTradeSettings> trades = tradesFor(session);
        if (trades.isEmpty()) {
            player.sendMessage(plugin.messages().prefix() + m("shop.no-trades", "&cいまは購入できる商品がありません。"));
            return;
        }

        int size = inventorySizeFor(trades.size());
        ShopHolder holder = new ShopHolder(session.sessionId(), trades, size);
        Inventory inventory = Bukkit.createInventory(
                holder,
                size,
                shopTitle(session)
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
        ItemStack item = tradeItem(trade);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + trade.material().name());
            List<String> lore = new ArrayList<>();
            lore.add(m("shop.lore.price", "&ePrice: {price}", "price", trade.price()));
            lore.add(m("shop.lore.shared-money", "&7Shared money: {balance}", "balance", session.sharedBalance()));
            if (!trade.canDestroy().isEmpty()) {
                lore.add(m("shop.lore.can-mine", "&7Can mine: {count} block types", "count", trade.canDestroy().size()));
            }
            if (trade.durability() != null) {
                lore.add(m("shop.lore.durability", "&7Durability: {durability}", "durability", trade.durability()));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack tradeItem(ShopTradeSettings trade) {
        ItemStack item = new ItemStack(trade.material(), trade.amount());
        ItemAdventurePredicateSupport.setCanBreak(item, trade.canDestroy());
        if (item.getItemMeta() instanceof Damageable damageable && trade.durability() != null) {
            int damage = Math.max(0, item.getType().getMaxDurability() - trade.durability());
            damageable.setDamage(damage);
            item.setItemMeta(damageable);
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
        return switch (session.state()) {
            case BETWEEN_ROUNDS -> true;
            case IN_ROUND -> session.alivePlayers().contains(player.getUniqueId())
                    && player.getGameMode() != GameMode.SPECTATOR;
            default -> false;
        };
    }

    private int inventorySizeFor(int tradeCount) {
        return Math.clamp(((tradeCount + 8) / 9) * 9, 9, 54);
    }

    private String shopTitle(GameSession session) {
        return ChatColor.DARK_GREEN + plugin.settings().shop().title()
                + ChatColor.GRAY + " $" + session.sharedBalance();
    }

    private String m(String key, String fallback, Object... replacements) {
        return plugin.messages().format(key, fallback, replacements);
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
