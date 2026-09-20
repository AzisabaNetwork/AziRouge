package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.EconomyQuotaSettings;
import net.azisaba.aziRouge.math.IntVector3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

public final class EconomyService {
    private final AziRouge plugin;

    public EconomyService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public long quotaForRound(int roundNumber) {
        EconomyQuotaSettings quota = plugin.settings().economy().quota();
        double rawQuota = (quota.base() + quota.perRound() * (double) Math.max(1, roundNumber))
                * quota.multiplier();
        if (rawQuota <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil(rawQuota);
    }

    public void prepareDeliveryChest(GameSession session) {
        ensureDeliveryChest(session, true);
    }

    private void ensureDeliveryChest(GameSession session, boolean clear) {
        Block block = deliveryChestBlock(session);
        if (!(block.getState() instanceof Chest)) {
            if (!block.getType().isAir()) {
                plugin.getLogger().warning("Replacing " + block.getType() + " at configured delivery chest location in "
                        + session.world().getName() + ".");
            }
            block.setType(Material.CHEST, false);
        }
        Chest chest = requireDeliveryChest(session);
        if (clear) {
            chest.getBlockInventory().clear();
        }
        chest.customName(Component.text(plugin.messages().text("delivery-chest.name", "納品箱")));
        chest.update(true, false);
    }

    public SellResult sellDeliveryChestLoot(GameSession session) {
        Map<Material, Long> prices = plugin.settings().economy().sellPrices();
        if (prices.isEmpty()) {
            return new SellResult(0L, 0);
        }

        ensureDeliveryChest(session, false);
        Inventory inventory = requireDeliveryChest(session).getBlockInventory();
        SellResult result = pricedContents(inventory, prices, true);
        if (result.totalAmount() > 0L) {
            session.addSharedBalance(result.totalAmount());
        }
        return result;
    }

    public long deliveryValue(GameSession session) {
        if (!(deliveryChestBlock(session).getState() instanceof Chest chest)) {
            return 0L;
        }
        return pricedContents(chest.getBlockInventory(), plugin.settings().economy().sellPrices(), false).totalAmount();
    }

    public ItemStack withValueLore(ItemStack source) {
        ItemStack item = source.clone();
        Long price = priceFor(plugin.settings().economy().sellPrices(), item.getType());
        ItemMeta meta = item.getItemMeta();
        if (price != null && meta != null) {
            meta.lore(List.of(LegacyComponentSerializer.legacySection().deserialize(
                    plugin.messages().format("treasure.sell-price", "&e価値: {price}", "price", price)
            )));
            item.setItemMeta(meta);
        }
        return item;
    }

    public QuotaResult evaluateQuota(GameSession session, int roundNumber, SellResult delivery) {
        long quota = quotaForRound(roundNumber);
        boolean achieved = delivery.totalAmount() >= quota;
        EconomyQuotaSettings settings = plugin.settings().economy().quota();
        QuotaProgress progress = QuotaProgress.afterRound(
                session.consecutiveQuotaMisses(),
                achieved,
                settings.maxConsecutiveMisses()
        );
        session.setConsecutiveQuotaMisses(progress.consecutiveMisses());
        return new QuotaResult(quota, achieved, progress);
    }

    public IntVector3 deliveryChestPosition() {
        return plugin.settings().economy().quota().deliveryChest();
    }

    public boolean isDeliveryChest(GameSession session, Block block) {
        IntVector3 position = deliveryChestPosition();
        return block.getWorld().getUID().equals(session.world().getUID())
                && block.getX() == position.x()
                && block.getY() == position.y()
                && block.getZ() == position.z();
    }

    private Block deliveryChestBlock(GameSession session) {
        IntVector3 position = deliveryChestPosition();
        if (!session.homeArea().contains(position.x(), position.y(), position.z())) {
            throw new IllegalStateException("economy.quota.delivery-chest must be inside home.area");
        }
        return session.world().getBlockAt(position.x(), position.y(), position.z());
    }

    private Chest requireDeliveryChest(GameSession session) {
        if (deliveryChestBlock(session).getState() instanceof Chest chest) {
            return chest;
        }
        throw new IllegalStateException("Configured delivery chest is missing in session " + session.sessionId());
    }

    private SellResult pricedContents(Inventory inventory, Map<Material, Long> prices, boolean remove) {
        ItemStack[] contents = inventory.getStorageContents();
        long totalAmount = 0L;
        int totalItems = 0;
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Long unitPrice = priceFor(prices, item.getType());
            if (unitPrice == null) {
                continue;
            }
            totalAmount += unitPrice * item.getAmount();
            totalItems += item.getAmount();
            if (remove) {
                contents[index] = null;
            }
        }
        if (remove) {
            inventory.setStorageContents(contents);
        }
        return new SellResult(totalAmount, totalItems);
    }

    private Long priceFor(Map<Material, Long> prices, Material material) {
        Long direct = prices.get(material);
        if (direct != null) {
            return direct;
        }
        return switch (material) {
            case COAL, COAL_ORE -> prices.getOrDefault(Material.COAL, 1L);
            case RAW_IRON, IRON_ORE -> prices.get(Material.IRON_INGOT);
            case RAW_GOLD, GOLD_ORE -> prices.get(Material.GOLD_INGOT);
            case DIAMOND_ORE -> prices.get(Material.DIAMOND);
            default -> null;
        };
    }

    public record SellResult(long totalAmount, int itemCount) {
    }

    public record QuotaResult(long quota, boolean achieved, QuotaProgress progress) {
    }
}
