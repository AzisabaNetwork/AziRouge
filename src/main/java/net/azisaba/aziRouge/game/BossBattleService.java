package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.BossBattleSettings;
import net.azisaba.aziRouge.math.BlockBox;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BossBattleService implements Listener {
    private static final String PREFIX = ChatColor.GOLD + "[Azirouge] " + ChatColor.RESET;

    private final AziRouge plugin;
    private final GameSessionManager sessionManager;
    private final Map<String, ActiveBossBattle> activeBattles = new HashMap<>();
    private final Map<UUID, Long> portalCooldownUntilMillis = new HashMap<>();
    private BukkitTask reviveTask;

    public BossBattleService(AziRouge plugin, GameSessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    public void start() {
        if (reviveTask != null) {
            return;
        }
        reviveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRevives, 1L, 1L);
    }

    public void shutdown() {
        if (reviveTask != null) {
            reviveTask.cancel();
            reviveTask = null;
        }
        for (String sessionId : List.copyOf(activeBattles.keySet())) {
            GameSession session = sessionManager.sessionById(sessionId).orElse(null);
            if (session != null) {
                clearBossBattle(session);
            }
        }
        activeBattles.clear();
        portalCooldownUntilMillis.clear();
    }

    public void clearBossBattle(GameSession session) {
        removeActiveBattle(session);
        session.clearBossBattle();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)) {
            return;
        }

        GameSession session = sessionManager.sessionForWorld(villager.getWorld()).orElse(null);
        if (session == null) {
            return;
        }

        BossBattleSettings battle = battleFor(villager).orElse(null);
        if (battle == null) {
            return;
        }

        event.setCancelled(true);
        challengeBoss(event.getPlayer(), session, battle);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntityType() != EntityType.EVOKER) {
            return;
        }

        GameSession session = sessionManager.sessionForWorld(event.getEntity().getWorld()).orElse(null);
        if (session == null || !session.isBossBattleActive() || session.isBossDefeated()) {
            return;
        }

        ActiveBossBattle active = activeBattles.get(session.sessionId());
        if (active == null) {
            return;
        }

        session.markBossDefeated();
        active.installReturnPortal(session.world());
        broadcast(session, ChatColor.GOLD + "Boss defeated. A return portal has opened.");
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendTitle(ChatColor.GOLD + "Boss Defeated", ChatColor.YELLOW + "Enter the portal to return", 10, 70, 20);
            }
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
        if (session == null || !session.isBossBattleActive() || !session.isBossDefeated()) {
            return;
        }

        ActiveBossBattle active = activeBattles.get(session.sessionId());
        if (active == null || !active.containsReturnPortal(to)) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownUntil = portalCooldownUntilMillis.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownUntil > now) {
            return;
        }
        portalCooldownUntilMillis.put(
                player.getUniqueId(),
                now + plugin.settings().boss().portalCooldownSeconds() * 1000L
        );

        active.returnedPlayers().add(player.getUniqueId());
        sessionManager.returnBossPlayerHome(session, player);
        if (hasEveryoneReturned(session, active)) {
            removeActiveBattle(session);
            sessionManager.finishBossBattleVictory(session);
        }
    }

    private void challengeBoss(Player player, GameSession session, BossBattleSettings battle) {
        if (!session.isMember(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.RED + "You are not a member of this session.");
            return;
        }
        if (session.state() != SessionState.LOBBY && session.state() != SessionState.BETWEEN_ROUNDS) {
            player.sendMessage(PREFIX + ChatColor.RED + "Boss battles can only be challenged between rounds.");
            return;
        }
        if (session.currentRound() < battle.minRound()) {
            player.sendMessage(PREFIX + ChatColor.RED + "This boss requires round " + battle.minRound()
                    + " or higher. Current round: " + session.currentRound());
            return;
        }
        if (session.isBossBattleActive()) {
            player.sendMessage(PREFIX + ChatColor.RED + "A boss battle is already active.");
            return;
        }

        List<UUID> participants = session.onlineMembers().stream()
                .filter(playerId -> Bukkit.getPlayer(playerId) != null)
                .toList();
        if (participants.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.RED + "There are no online session members.");
            return;
        }

        Location destination = new Location(
                session.world(),
                battle.destination().x() + 0.5D,
                battle.destination().y(),
                battle.destination().z() + 0.5D,
                battle.yaw(),
                0.0F
        );
        ActiveBossBattle active = new ActiveBossBattle(
                battle,
                battle.returnPortalArea().offset(battle.destination())
        );
        activeBattles.put(session.sessionId(), active);
        try {
            sessionManager.beginBossBattle(session, battle.id(), destination, participants);
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "oomurabito spawn " + session.world().getName() + " "
                            + battle.destination().x() + " "
                            + battle.destination().y() + " "
                            + battle.destination().z()
            );
        } catch (RuntimeException ex) {
            activeBattles.remove(session.sessionId());
            throw ex;
        }
    }

    private void tickRevives() {
        for (GameSession session : sessionManager.sessions()) {
            if (!session.isBossBattleActive() || session.isBossDefeated() || session.state() != SessionState.IN_ROUND) {
                continue;
            }
            ActiveBossBattle active = activeBattles.get(session.sessionId());
            if (active == null) {
                continue;
            }
            tickSessionRevives(session, active);
        }
    }

    private void tickSessionRevives(GameSession session, ActiveBossBattle active) {
        Set<UUID> deadPlayers = new HashSet<>(session.deadPlayers());
        active.reviveProgress().keySet().removeIf(playerId -> !deadPlayers.contains(playerId));

        for (UUID deadPlayerId : deadPlayers) {
            Location deathLocation = session.bossDeathLocation(deadPlayerId);
            if (deathLocation == null) {
                active.reviveProgress().remove(deadPlayerId);
                continue;
            }

            Player rescuer = findRescuer(session, deadPlayerId, deathLocation);
            if (rescuer == null) {
                active.reviveProgress().remove(deadPlayerId);
                continue;
            }

            RevivalProgress progress = active.reviveProgress().get(deadPlayerId);
            if (progress == null || !progress.rescuerId().equals(rescuer.getUniqueId())) {
                progress = new RevivalProgress(rescuer.getUniqueId(), 0);
            }
            progress = progress.nextTick();
            active.reviveProgress().put(deadPlayerId, progress);

            if (progress.ticks() >= plugin.settings().boss().reviveHoldTicks()) {
                Player deadPlayer = Bukkit.getPlayer(deadPlayerId);
                active.reviveProgress().remove(deadPlayerId);
                if (deadPlayer != null && sessionManager.reviveBossPlayer(session, deadPlayer, deathLocation)) {
                    rescuer.sendMessage(PREFIX + ChatColor.GREEN + "Revived " + deadPlayer.getName() + ".");
                }
            }
        }
    }

    private Player findRescuer(GameSession session, UUID deadPlayerId, Location deathLocation) {
        double radius = plugin.settings().boss().reviveRadius();
        double radiusSquared = radius * radius;
        for (UUID alivePlayerId : session.alivePlayers()) {
            if (alivePlayerId.equals(deadPlayerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(alivePlayerId);
            if (player == null || !player.isSneaking() || player.isDead()) {
                continue;
            }
            if (deathLocation.getWorld() != null
                    && player.getWorld().getUID().equals(deathLocation.getWorld().getUID())
                    && player.getLocation().distanceSquared(deathLocation) <= radiusSquared) {
                return player;
            }
        }
        return null;
    }

    private Optional<BossBattleSettings> battleFor(Villager villager) {
        return plugin.settings().boss().battles().stream()
                .filter(battle -> villager.getScoreboardTags().contains(battle.villagerTag()))
                .findFirst();
    }

    private boolean hasEveryoneReturned(GameSession session, ActiveBossBattle active) {
        for (UUID playerId : session.onlineMembers()) {
            if (Bukkit.getPlayer(playerId) != null && !active.returnedPlayers().contains(playerId)) {
                return false;
            }
        }
        return true;
    }

    private void broadcast(GameSession session, String message) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(PREFIX + message);
            }
        }
    }

    private void removeActiveBattle(GameSession session) {
        ActiveBossBattle active = activeBattles.remove(session.sessionId());
        if (active != null) {
            active.restorePortalBlocks();
            for (UUID playerId : session.members()) {
                portalCooldownUntilMillis.remove(playerId);
            }
        }
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private static final class ActiveBossBattle {
        private final BossBattleSettings settings;
        private final BlockBox returnPortalArea;
        private final List<BlockState> savedPortalBlocks = new ArrayList<>();
        private final Set<UUID> returnedPlayers = new HashSet<>();
        private final Map<UUID, RevivalProgress> reviveProgress = new HashMap<>();

        private ActiveBossBattle(BossBattleSettings settings, BlockBox returnPortalArea) {
            this.settings = settings;
            this.returnPortalArea = returnPortalArea;
        }

        private Set<UUID> returnedPlayers() {
            return returnedPlayers;
        }

        private Map<UUID, RevivalProgress> reviveProgress() {
            return reviveProgress;
        }

        private boolean containsReturnPortal(Location location) {
            return location.getWorld() != null
                    && returnPortalArea.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private void installReturnPortal(World world) {
            restorePortalBlocks();
            for (int x = returnPortalArea.minX(); x <= returnPortalArea.maxX(); x++) {
                for (int y = returnPortalArea.minY(); y <= returnPortalArea.maxY(); y++) {
                    for (int z = returnPortalArea.minZ(); z <= returnPortalArea.maxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        savedPortalBlocks.add(block.getState());
                        block.setType(settings.returnPortalMaterial(), false);
                    }
                }
            }
        }

        private void restorePortalBlocks() {
            for (BlockState state : savedPortalBlocks) {
                state.update(true, false);
            }
            savedPortalBlocks.clear();
        }
    }

    private record RevivalProgress(UUID rescuerId, int ticks) {
        private RevivalProgress nextTick() {
            return new RevivalProgress(rescuerId, ticks + 1);
        }
    }
}
