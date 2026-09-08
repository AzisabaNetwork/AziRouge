package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.math.BlockBox;
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
                    Material.COMPASS, preparing ? "preparing" : "depart", preparing ? "&7出発の準備中…" : "&6出発！");
            BlockBox returnArea = plugin.portalService().returnArea(session);
            if (returnArea != null && session.state() == SessionState.IN_ROUND) {
                mark(wanted, session.sessionId() + ":back", center(session, returnArea), Material.LANTERN, "return", "&a帰還！");
            }
            for (Villager villager : session.world().getEntitiesByClass(Villager.class)) {
                Location at = villager.getLocation();
                if (!session.homeArea().contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())
                        || villager.getScoreboardTags().stream().anyMatch(tag -> Set.of("create_session", "join_session", "leave_session", "start_round", "end_round", "round", "menu").contains(tag))
                        || plugin.settings().boss().battles().stream().anyMatch(battle -> villager.getScoreboardTags().contains(battle.villagerTag()))) continue;
                mark(wanted, session.sessionId() + ":shop:" + villager.getUniqueId(), at.clone().add(0, 2.2, 0),
                        Material.EMERALD, "shop", "&e旅支度");
                for (Player player : session.world().getPlayers()) {
                    if (session.isMember(player.getUniqueId()) && player.getLocation().distanceSquared(at) < 16
                            && DepartureGuard.canPrepare(session.state(), session.roundState())) {
                        hintOnce(plugin, player, "shop", "旅の備えに。右クリックで買い物できます。資金は仲間と共有です。");
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

    private void mark(Set<String> wanted, String id, Location location, Material material, String key, String fallback) {
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
                display.setGlowColorOverride(material == Material.LANTERN ? Color.LIME : Color.YELLOW);
            });
            TextDisplay text = location.getWorld().spawn(location, TextDisplay.class, display -> {
                setup(display);
                display.setShadowed(true);
                display.setSeeThrough(false);
                display.setBackgroundColor(Color.fromARGB(90, 15, 15, 15));
            });
            current = new Landmark(icon, text);
            landmarks.put(id, current);
        }
        Component label = LegacyComponentSerializer.legacySection().deserialize(plugin.messages().text("journey.landmark." + key, fallback));
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

    public static void hintOnce(AziRouge plugin, Player player, String stage, String fallback) {
        NamespacedKey key = new NamespacedKey(plugin, "journey_" + stage);
        if (player.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        player.sendActionBar(Component.text(plugin.messages().text("journey.hint." + stage, fallback)));
        player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    private record Landmark(ItemDisplay icon, TextDisplay text) {
        void remove() {
            icon.remove();
            text.remove();
        }
    }
}
