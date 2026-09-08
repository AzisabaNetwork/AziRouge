package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.PortalSettings;
import net.azisaba.aziRouge.dungeon.PlacedPiece;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PortalService implements Listener {
    private final AziRouge plugin;
    private final GameSessionManager sessionManager;
    private final Map<String, RoundPortals> roundPortalsBySessionId = new HashMap<>();
    private final Map<UUID, Long> cooldownUntilMillis = new HashMap<>();

    public PortalService(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    public void installRoundPortals(GameSession session) {
        clearRoundPortals(session);
        if (session.currentDungeonOrigin() == null) {
            return;
        }

        PortalSettings settings = plugin.settings().portals();
        Location dungeonDestination = dungeonDestination(session, settings);
        BlockBox homeArea = settings.homeToDungeon().area();
        BlockBox dungeonPortalArea = dungeonToHomeArea(session, settings);

        roundPortalsBySessionId.put(
                session.sessionId(),
                new RoundPortals(
                        homeArea,
                        dungeonPortalArea,
                        dungeonDestination,
                        session.returnSpawnLocation(),
                        settings.homeToDungeon().destinationYawOffset(),
                        settings.dungeonToHome().destinationYawOffset()
                )
        );
        logPortalLocations(
                session,
                homeArea,
                dungeonPortalArea,
                dungeonDestination,
                session.returnSpawnLocation(),
                settings.homeToDungeon().destinationYawOffset(),
                settings.dungeonToHome().destinationYawOffset()
        );
    }

    public void clearRoundPortals(GameSession session) {
        roundPortalsBySessionId.remove(session.sessionId());
        for (UUID playerId : session.members()) {
            cooldownUntilMillis.remove(playerId);
        }
    }

    public void shutdown() {
        for (String sessionId : List.copyOf(roundPortalsBySessionId.keySet())) {
            GameSession session = sessionManager.sessionById(sessionId).orElse(null);
            if (session != null) {
                clearRoundPortals(session);
            }
        }
        roundPortalsBySessionId.clear();
        cooldownUntilMillis.clear();
    }

    public void reload() {
        shutdown();
        for (GameSession session : sessionManager.sessions()) {
            if (session.state() == SessionState.IN_ROUND && !session.isBossBattleActive()) installRoundPortals(session);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || sameBlock(event.getFrom(), to)) {
            return;
        }

        Player player = event.getPlayer();
        GameSession session = sessionManager.sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || !player.getWorld().equals(session.world()) || player.isDead()) {
            return;
        }

        if (DepartureGuard.canPrepare(session.state(), session.roundState())) {
            BlockBox entrance = plugin.settings().portals().homeToDungeon().area();
            if (contains(entrance, to) && !contains(entrance, event.getFrom())) {
                plugin.gameMenuService().showStartRoundDialog(player);
            }
            return;
        }
        if (!canUsePortal(session, player)) return;
        RoundPortals roundPortals = roundPortalsBySessionId.get(session.sessionId());
        if (roundPortals == null) {
            return;
        }

        if (contains(roundPortals.homeToDungeonArea(), to)) {
            enterDungeon(player, session);
            return;
        }
        if (contains(roundPortals.dungeonToHomeArea(), to)) {
            teleportWithCooldown(
                    player,
                    roundPortals.homeDestination(),
                    roundPortals.homeYawOffset(),
                    plugin.messages().text("journey.return", "&a帰還！"),
                    false
            );
        }
    }

    private boolean canUsePortal(GameSession session, Player player) {
        return session.state() == SessionState.IN_ROUND
                && session.roundState() == RoundState.ACTIVE && !session.isBossBattleActive() && !player.isDead()
                && player.getWorld().getUID().equals(session.world().getUID())
                && session.isMember(player.getUniqueId())
                && session.alivePlayers().contains(player.getUniqueId())
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void teleportWithCooldown(Player player, Location destination, float yawOffset, String title, boolean enteredDungeon) {
        long now = System.currentTimeMillis();
        long cooldownUntil = cooldownUntilMillis.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownUntil > now) {
            return;
        }

        Location target = destination.clone();
        target.setYaw(normalizeYaw(player.getLocation().getYaw() + yawOffset));
        target.setPitch(player.getLocation().getPitch());
        if (player.teleport(target)) {
            player.sendTitle(title, "", 5, 35, 10);
            cooldownUntilMillis.put(player.getUniqueId(), now + plugin.settings().portals().cooldownSeconds() * 1000L);
            player.playSound(player.getLocation(), enteredDungeon ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.35F, 1.2F);
            JourneyDisplayService.hintOnce(plugin, player, enteredDungeon ? "explore" : "return",
                    enteredDungeon ? "宝を探そう。帰り道も忘れずに。" : "おかえり。仲間が揃ったら戦利品を精算しよう。");

        } else {
            cooldownUntilMillis.put(player.getUniqueId(), now + 1000L);
            player.sendActionBar(net.kyori.adventure.text.Component.text(plugin.messages().text("journey.teleport-failed", "道が塞がれています。少し待って、もう一度。")));
        }
    }

    public void enterDungeon(Player player, GameSession session) {
        RoundPortals portals = roundPortalsBySessionId.get(session.sessionId());
        if (portals == null || !canUsePortal(session, player)) return;
        teleportWithCooldown(player, portals.dungeonDestination(), portals.dungeonYawOffset(),
                plugin.messages().text("journey.depart-title", "&6出発！"), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldownUntilMillis.remove(event.getPlayer().getUniqueId());
    }

    public BlockBox returnArea(GameSession session) {
        RoundPortals portals = roundPortalsBySessionId.get(session.sessionId());
        return portals == null ? null : portals.dungeonToHomeArea();
    }
    private Location dungeonDestination(GameSession session, PortalSettings settings) {
        IntVector3 offset = settings.homeToDungeon().destinationOffset();
        IntVector3 destination = session.currentDungeonOrigin().add(offset);
        return new Location(session.world(), destination.x() + 0.5D, destination.y(), destination.z() + 0.5D);
    }

    private void logPortalLocations(
            GameSession session,
            BlockBox homeToDungeonArea,
            BlockBox dungeonToHomeArea,
            Location dungeonDestination,
            Location homeDestination,
            float dungeonYawOffset,
            float homeYawOffset
    ) {
        plugin.getLogger().info("[portal-debug] session=" + session.sessionId()
                + " world=" + session.world().getName()
                + " homeToDungeonArea=" + format(homeToDungeonArea)
                + " dungeonDestination=" + format(dungeonDestination)
                + " dungeonYawOffset=" + dungeonYawOffset
                + " dungeonToHomeArea=" + format(dungeonToHomeArea)
                + " homeDestination=" + format(homeDestination)
                + " homeYawOffset=" + homeYawOffset);
    }

    private BlockBox dungeonToHomeArea(GameSession session, PortalSettings settings) {
        PlacedPiece startPiece = session.placedPieces().stream()
                .filter(piece -> piece.depth() == 0)
                .findFirst()
                .orElse(null);
        if (startPiece == null) {
            return BlockBox.fromPoints(session.currentDungeonOrigin(), session.currentDungeonOrigin());
        }
        return settings.dungeonToHome().area()
                .rotate(startPiece.rotation())
                .offset(startPiece.origin());
    }

    private boolean contains(BlockBox area, Location location) {
        return location.getWorld() != null
                && area.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private String format(BlockBox area) {
        return area.minX() + "," + area.minY() + "," + area.minZ()
                + "->" + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    private String format(Location location) {
        return location.getWorld().getName()
                + ":" + location.getX() + "," + location.getY() + "," + location.getZ();
    }

    private float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0F;
        if (normalized <= -180.0F) {
            return normalized + 360.0F;
        }
        if (normalized > 180.0F) {
            return normalized - 360.0F;
        }
        return normalized;
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private record RoundPortals(
            BlockBox homeToDungeonArea,
            BlockBox dungeonToHomeArea,
            Location dungeonDestination,
            Location homeDestination,
            float dungeonYawOffset,
            float homeYawOffset
    ) {
    }
}
