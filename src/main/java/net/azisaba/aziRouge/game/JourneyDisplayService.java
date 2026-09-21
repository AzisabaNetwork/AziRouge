package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Quiet, local landmarks; no per-tick particles or repeated tutorial messages. */
public final class JourneyDisplayService {
    private final AziRouge plugin;
    private final Map<String, Landmark> landmarks = new HashMap<>();
    private BukkitTask task;

    public JourneyDisplayService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        shutdown();
        if (plugin.getConfig().getBoolean("journey.enabled", true)) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, 20L);
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        landmarks.values().forEach(Landmark::remove);
        landmarks.clear();
    }

    public void clearSession(String sessionId) {
        landmarks.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(sessionId + ":")) return false;
            entry.getValue().remove();
            return true;
        });
    }

    private void refresh() {
        Set<String> wanted = new HashSet<>();
        for (GameSession session : plugin.gameSessionManager().sessions()) {
            if (session.state() == SessionState.CLOSING || session.state() == SessionState.GAME_OVER || session.isBossBattleActive()) continue;
            boolean preparing = session.roundState() == RoundState.PREPARING;
            mark(wanted, session.sessionId() + ":out", center(session, plugin.settings().portals().homeToDungeon().area()),
                    Material.COMPASS, preparing ? "preparing" : "depart", preparing ? "&7準備中…" : "&6出発");
            BlockBox returnArea = plugin.portalService().returnArea(session);
            if (returnArea != null && session.state() == SessionState.IN_ROUND) {
                mark(wanted, session.sessionId() + ":back", center(session, returnArea), Material.LANTERN, "return", "&a帰還");
            }
            IntVector3 deliveryChest = plugin.economyService().deliveryChestPosition();
            Location deliveryLocation = new Location(
                    session.world(),
                    deliveryChest.x() + 0.5D,
                    deliveryChest.y() + 1.2D,
                    deliveryChest.z() + 0.5D
            );
            int maxAnger = plugin.settings().economy().quota().maxConsecutiveMisses();
            mark(wanted, session.sessionId() + ":delivery", deliveryLocation,
                    Material.CHEST, "delivery-status",
                    "&6納品箱\n&f{delivered}&8 / &f{quota}\n&7資金 &f{balance}\n&c怒りゲージ &f{anger}",
                    "delivered", plugin.economyService().deliveryValue(session),
                    "quota", plugin.economyService().quotaForRound(DepartureGuard.dayToStart(session.currentRound())),
                    "balance", session.sharedBalance(),
                    "anger", AngerGauge.render(session.consecutiveQuotaMisses(), maxAnger));
            for (Villager villager : session.world().getEntitiesByClass(Villager.class)) {
                Location at = villager.getLocation();
                if (!session.homeArea().contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())
                        || villager.getScoreboardTags().stream().anyMatch(tag -> Set.of("create_session", "join_session", "leave_session", "start_round", "end_round", "round", "menu").contains(tag))
                        || plugin.settings().boss().battles().stream().anyMatch(battle -> villager.getScoreboardTags().contains(battle.villagerTag()))) continue;
                mark(wanted, session.sessionId() + ":shop:" + villager.getUniqueId(), at.clone().add(0, 2.2, 0),
                        Material.EMERALD, "shop", "&e商人");
                for (Player player : session.world().getPlayers()) {
                    if (session.isMember(player.getUniqueId()) && player.getLocation().distanceSquared(at) < 16
                            && DepartureGuard.canPrepare(session.state(), session.roundState())) {
                        hintOnce(plugin, player, "shop", "&e右クリックで買い物。お金は仲間と共有だ。");
                    }
                }
            }
        }
        landmarks.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            entry.getValue().remove();
            return true;
        });
    }

    private Location center(GameSession session, BlockBox box) {
        return new Location(session.world(), (box.minX() + box.maxX() + 1) / 2.0,
                box.maxY() + plugin.getConfig().getDouble("journey.entrance-height", 1.2),
                (box.minZ() + box.maxZ() + 1) / 2.0);
    }

    private void mark(Set<String> wanted, String id, Location location, Material material, String key, String fallback, Object... replacements) {
        wanted.add(id);
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Landmark current = landmarks.get(id);
        if (current == null || !current.text.isValid() || !current.icon.isValid()) {
            if (current != null) current.remove();
            ItemDisplay icon = location.getWorld().spawn(location.clone().add(0, 0.6, 0), ItemDisplay.class, display -> {
                setup(display);
                display.setItemStack(new ItemStack(material));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                display.setGlowing(true);
                display.setGlowColorOverride(glowColor(material));
            });
            TextDisplay text = location.getWorld().spawn(location, TextDisplay.class, display -> {
                setup(display);
                display.setShadowed(true);
                display.setSeeThrough(false);
                display.setBackgroundColor(Color.fromARGB(160, 8, 8, 8));
                display.setLineWidth(140);
            });
            current = new Landmark(icon, text);
            landmarks.put(id, current);
        }
        Component label = LegacyComponentSerializer.legacySection().deserialize(
                plugin.messages().format("journey.landmark." + key, fallback, replacements)
        );
        if (!label.equals(current.text.text())) current.text.text(label);
        if (current.text.getLocation().distanceSquared(location) > 0.01) {
            current.text.teleport(location);
            current.icon.teleport(location.clone().add(0, 0.6, 0));
        }
    }

    private void setup(Display display) {
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setViewRange((float) Math.clamp(plugin.getConfig().getDouble("journey.view-distance", 24), 4, 64) / 64F);
    }

    public static void hintOnce(AziRouge plugin, Player player, String stage, String fallback, Object... replacements) {
        NamespacedKey key = new NamespacedKey(plugin, "journey_" + stage);
        NamespacedKey pendingKey = new NamespacedKey(plugin, "journey_" + stage + "_pending");
        if (player.getPersistentDataContainer().has(key, PersistentDataType.BYTE)
                || player.getPersistentDataContainer().has(pendingKey, PersistentDataType.BYTE)) return;
        player.getPersistentDataContainer().set(pendingKey, PersistentDataType.BYTE, (byte) 1);
        Component line = plugin.messages().component("journey.hint." + stage, fallback, replacements);
        player.sendActionBar(line);
        // ponytail: action bars vanish in ~3s, refresh so the first hint stays readable
        for (int i = 1; i <= 4; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getPersistentDataContainer().has(pendingKey, PersistentDataType.BYTE)) {
                    player.sendActionBar(line);
                }
            }, i * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.getPersistentDataContainer().remove(pendingKey);
            if (player.isOnline()) {
                player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            }
        }, 90L);
    }

    public void showDeliveryHint(Player player) {
        NamespacedKey key = new NamespacedKey(plugin, "journey_delivery-rules");
        if (player.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        player.sendMessage(plugin.messages().prefixed(
                "journey.hint.delivery-rules",
                "納品箱に入れた評価額でノルマを判定する。精算でその額が共有資金に入る。ノルマ分は引かれない。"
        ));
        player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    public void showBedHint(Player player) {
        NamespacedKey key = new NamespacedKey(plugin, "journey_bed_deadline");
        if (player.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        player.sendMessage(plugin.messages().prefixed(
                "journey.hint.bed-deadline",
                "{deadline}までに眠れていないと、その日は脱落だ。生存者の{percentage}%以上が{seconds}秒眠るか、全員が眠ると翌朝になる。",
                "percentage", plugin.settings().roundTiming().minimumSleepingPercentage(),
                "seconds", plugin.settings().roundTiming().sleepDelaySeconds(),
                "deadline", RoundClock.format(plugin.settings().roundTiming().deadlineTimeTicks())
        ));
        player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    private static Color glowColor(Material material) {
        return switch (material) {
            case LANTERN -> Color.LIME;
            case EMERALD -> Color.AQUA;
            case CHEST -> Color.ORANGE;
            default -> Color.YELLOW;
        };
    }

    private record Landmark(ItemDisplay icon, TextDisplay text) {
        void remove() {
            icon.remove();
            text.remove();
        }
    }
}
