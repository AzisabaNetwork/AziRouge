package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.ShopTradeSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
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
        GameSession session = sessionManager.sessionById(holder.sessionId()).orElse(null);
        if (session != null && requiresConfirmation(session, trade)) {
            plugin.confirmationService().request(
                    player,
                    plugin.messages().format(
                            "shop.confirm-expensive",
                            "{amount}個を {price} で買う。共有資金 {balance} → {after}。",
                            "amount", trade.amount(),
                            "price", trade.price(),
                            "balance", session.sharedBalance(),
                            "after", session.sharedBalance() - trade.price()
                    ),
                    () -> handlePurchase(player, holder, trade, inventory)
            );
        } else {
            handlePurchase(player, holder, trade, inventory);
        }
    }

    private void handlePurchase(Player player, ShopHolder holder, ShopTradeSettings trade, Inventory inventory) {
        GameSession session = sessionManager.sessionById(holder.sessionId()).orElse(null);
        if (session == null || !canUseShop(player, session) || session.state() != holder.state
                || session.currentRound() != holder.round || !session.runId().equals(holder.runId)) {
            player.closeInventory();
            player.sendMessage(plugin.messages().prefix() + m("shop.unavailable", "&cいまは店が使えない。"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7F, 1.0F);
            return;
        }

        if (session.sharedBalance() < trade.price()) {
            player.sendMessage(plugin.messages().prefix() + m("shop.not-enough-money-detail", "&c共有資金が足りない。価格 {price} / 残高 {balance}", "price", trade.price(), "balance", session.sharedBalance()));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8F, 0.9F);
            return;
        }

        ItemStack purchased = tradeItem(trade);
        if (!PlayerInventorySupport.canFit(player.getInventory(), purchased)) {
            player.sendMessage(plugin.messages().prefix() + m("shop.inventory-full", "&c持ち物がいっぱいだ。"));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.7F, 0.8F);
            return;
        }

        if (!session.withdrawSharedBalance(trade.price())) {
            player.sendMessage(plugin.messages().prefix() + m("shop.not-enough-money", "&c共有資金が足りない。"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8F, 0.9F);
            return;
        }
        player.getInventory().addItem(purchased);
        player.sendMessage(plugin.messages().prefix() + m("shop.purchase-complete", "&a買った。-{price}  &7共有資金 {balance}", "price", trade.price(), "balance", session.sharedBalance()));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9F, 1.25F);
        refreshShop(inventory, holder, session);
    }

    private void openShop(Player player, GameSession session) {
        if (!canUseShop(player, session)) {
            player.sendMessage(plugin.messages().prefix() + m("shop.only-in-game", "&c店はロビーか探索中だけ使える。"));
            return;
        }

        List<ShopTradeSettings> trades = tradesFor(session);
        if (trades.isEmpty()) {
            player.sendMessage(plugin.messages().prefix() + m("shop.no-trades", "&cいま買える品はない。"));
            return;
        }

        int size = inventorySizeFor(trades.size());
        ShopHolder holder = new ShopHolder(session, trades, size);
        Inventory inventory = Bukkit.createInventory(
                holder,
                size,
                shopTitle(session)
        );
        holder.setInventory(inventory);
        refreshShop(inventory, holder, session);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.45F, 1.2F);
    }

    private void refreshShop(Inventory inventory, ShopHolder holder, GameSession session) {
        inventory.clear();
        List<ShopTradeSettings> trades = holder.trades();
        for (int slot = 0; slot < trades.size() && slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, displayItem(trades.get(slot), session, slot == 0));
        }
    }

    private ItemStack displayItem(ShopTradeSettings trade, GameSession session, boolean showHotbarLimit) {
        ItemStack item = tradeItem(trade);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.translatable(trade.material().translationKey())
                    .color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(plain(" ×" + trade.amount(), NamedTextColor.WHITE))
                    .append(plain("  " + trade.price(), NamedTextColor.GOLD)));
            List<Component> lore = new ArrayList<>();
            lore.add(loreLine("shop.lore.price", "&e価格 {price}", "price", trade.price()));
            lore.add(loreLine("shop.lore.shared-money", "&7共有資金 {balance}", "balance", session.sharedBalance()));
            lore.add(loreLine("shop.lore.after-purchase", "&7購入後 {balance}", "balance", Math.max(0L, session.sharedBalance() - trade.price())));
            if (showHotbarLimit) {
                lore.add(loreLine("shop.lore.hotbar-limit", "&c探索中の持ち物は、ほぼホットバー9枠だけ。"));
            }
            if (requiresConfirmation(session, trade)) {
                lore.add(loreLine("shop.lore.confirmation", "&6金額が大きいので、もう一度クリックして確定する。"));
            }
            if (!trade.canDestroy().isEmpty()) {
                lore.add(loreLine("shop.lore.can-mine", "&7壊せるブロック {count}種類", "count", trade.canDestroy().size()));
            }
            if (trade.durability() != null) {
                lore.add(loreLine("shop.lore.durability", "&7耐久 {durability}", "durability", trade.durability()));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component loreLine(String key, String fallback, Object... replacements) {
        return plugin.messages().component(key, fallback, replacements).decoration(TextDecoration.ITALIC, false);
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
        if (session.state() == SessionState.LOBBY) {
            return plugin.settings().shop().betweenRoundTrades();
        }
        return List.of();
    }

    private boolean requiresConfirmation(GameSession session, ShopTradeSettings trade) {
        long balance = session.sharedBalance();
        return balance > 0L && trade.price() <= balance && trade.price() >= Math.ceilDiv(balance, 2L);
    }

    private boolean canUseShop(Player player, GameSession session) {
        if (!player.getWorld().getUID().equals(session.world().getUID()) || !session.isMember(player.getUniqueId())) {
            return false;
        }
        return switch (session.state()) {
            case LOBBY -> session.roundState() != RoundState.PREPARING;
            case IN_ROUND -> session.alivePlayers().contains(player.getUniqueId())
                    && player.getGameMode() != GameMode.SPECTATOR;
            default -> false;
        };
    }

    private int inventorySizeFor(int tradeCount) {
        return Math.clamp(((tradeCount + 8) / 9) * 9, 9, 54);
    }

    private String shopTitle(GameSession session) {
        return m("shop.window-title", "&2{title}  &7資金 {balance}",
                "title", plugin.settings().shop().title(),
                "balance", session.sharedBalance());
    }

    private String m(String key, String fallback, Object... replacements) {
        return plugin.messages().format(key, fallback, replacements);
    }

    private static final class ShopHolder implements InventoryHolder {
        private final String sessionId;
        private final java.util.UUID runId;
        private final SessionState state;
        private final int round;
        private final List<ShopTradeSettings> trades;
        private Inventory inventory;

        private ShopHolder(GameSession session, List<ShopTradeSettings> trades, int size) {
            this.sessionId = session.sessionId();
            this.runId = session.runId();
            this.state = session.state();
            this.round = session.currentRound();
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
