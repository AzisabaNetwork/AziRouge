package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SessionGameplayService implements Listener {
    private static final String BLOCKER_KEY = "inventory_blocker";
    private static final int FOOD_MAX = 20;
    private static final int SPRINT_MIN_FOOD = 6;

    private final AziRouge plugin;
    private final GameSessionManager sessionManager;
    private final Map<UUID, Double> foodLevels = new HashMap<>();
    private BukkitTask task;

    public SessionGameplayService(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayers, 1L, 5L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeBlockers(player.getInventory());
        }
        foodLevels.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isSessionPlayer(event.getPlayer()) && !sessionManager.isActivePlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isSessionPlayer(event.getPlayer()) && !sessionManager.isActivePlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isSessionPlayer(player) && !sessionManager.isActivePlaying(player)) {
            event.setCancelled(true);
            fixVitals(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && isSessionPlayer(player) && !sessionManager.isActivePlaying(player)) {
            event.setCancelled(true);
            fixVitals(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !sessionManager.isActivePlaying(player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (isBlocker(current) || event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() instanceof PlayerInventory && event.getSlot() >= 9 && event.getSlot() <= 35) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !sessionManager.isActivePlaying(player)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            int playerSlot = rawSlot - topSize;
            if (playerSlot >= 9 && playerSlot <= 35) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isBlocker(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isBlocker);
    }

    private void tickPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isSessionPlayer(player)) {
                foodLevels.remove(player.getUniqueId());
                removeBlockers(player.getInventory());
                continue;
            }

            if (sessionManager.isActivePlaying(player)) {
                applyBlockers(player);
                updateSprintFood(player);
            } else {
                sessionManager.ensureSpectatorTarget(player);
                foodLevels.put(player.getUniqueId(), (double) FOOD_MAX);
                removeBlockers(player.getInventory());
                fixVitals(player);
            }
        }
    }

    private void updateSprintFood(Player player) {
        double food = foodLevels.getOrDefault(player.getUniqueId(), (double) Math.max(0, player.getFoodLevel()));
        if (player.getCurrentInput().isSprint() || player.isSprinting()) {
            food -= plugin.settings().player().sprintDrainPerSecond() / 4;
        } else {
            food += plugin.settings().player().sprintRecoveryPerSecond() / 4;
        }
        food = Math.clamp(food, 0.0D, FOOD_MAX);
        foodLevels.put(player.getUniqueId(), food);
        player.setFoodLevel((int) Math.floor(food));
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
    }

    private void fixVitals(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && !player.isDead()) {
            try {
                player.setHealth(maxHealth.getValue());
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid health states during death/respawn transitions.
            }
        }
        player.setFoodLevel(FOOD_MAX);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
    }

    private boolean isSessionPlayer(Player player) {
        return sessionManager.sessionForPlayer(player.getUniqueId()).isPresent()
                && sessionManager.sessionForWorld(player.getWorld()).isPresent();
    }

    private void applyBlockers(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isBlocker(item)) {
                continue;
            }
            if (item != null && !item.getType().isAir()) {
                if (PlayerInventorySupport.canFitHotbar(inventory, item)) {
                    PlayerInventorySupport.addToHotbar(inventory, item);
                } else {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
            inventory.setItem(slot, blocker());
        }
    }

    private void removeBlockers(PlayerInventory inventory) {
        for (int slot = 9; slot <= 35; slot++) {
            if (isBlocker(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
    }

    private ItemStack blocker() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(plugin, BLOCKER_KEY),
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isBlocker(ItemStack item) {
        if (item == null || item.getType() != Material.GRAY_STAINED_GLASS_PANE || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(plugin, BLOCKER_KEY),
                PersistentDataType.BYTE
        );
    }
}
