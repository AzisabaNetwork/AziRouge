package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.PortalSettings;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.ArrayList;
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
        Material material = settings.dungeonToHome().material();
        Location dungeonDestination = dungeonDestination(session, settings);
        BlockBox homeArea = settings.homeToDungeon().area();
        BlockBox dungeonPortalBlocks = portalFloor(dungeonDestination);
        BlockBox dungeonPortalArea = portalTriggerArea(dungeonDestination);

        List<BlockState> originalBlocks = new ArrayList<>();
        placePortalBlocks(session.world(), homeArea, material, originalBlocks);
        placePortalBlocks(session.world(), dungeonPortalBlocks, material, originalBlocks);

        roundPortalsBySessionId.put(
                session.sessionId(),
                new RoundPortals(homeArea, dungeonPortalArea, dungeonDestination, session.spawnLocation(), originalBlocks)
        );
    }

    public void clearRoundPortals(GameSession session) {
        RoundPortals roundPortals = roundPortalsBySessionId.remove(session.sessionId());
        if (roundPortals == null) {
            return;
        }
        for (int index = roundPortals.originalBlocks().size() - 1; index >= 0; index--) {
            roundPortals.originalBlocks().get(index).update(true, false);
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

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || sameBlock(event.getFrom(), to)) {
            return;
        }

        Player player = event.getPlayer();
        GameSession session = sessionManager.sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || !canUsePortal(session, player)) {
            return;
        }

        RoundPortals roundPortals = roundPortalsBySessionId.get(session.sessionId());
        if (roundPortals == null) {
            return;
        }

        if (contains(roundPortals.homeToDungeonArea(), to)) {
            teleportWithCooldown(player, roundPortals.dungeonDestination());
            return;
        }
        if (contains(roundPortals.dungeonToHomeArea(), to)) {
            teleportWithCooldown(player, roundPortals.homeDestination());
        }
    }

    private boolean canUsePortal(GameSession session, Player player) {
        return session.state() == SessionState.IN_ROUND
                && player.getWorld().getUID().equals(session.world().getUID())
                && session.isMember(player.getUniqueId())
                && session.alivePlayers().contains(player.getUniqueId())
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void teleportWithCooldown(Player player, Location destination) {
        long now = System.currentTimeMillis();
        long cooldownUntil = cooldownUntilMillis.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownUntil > now) {
            return;
        }

        cooldownUntilMillis.put(
                player.getUniqueId(),
                now + plugin.settings().portals().cooldownSeconds() * 1000L
        );
        player.teleport(destination.clone());
    }

    private Location dungeonDestination(GameSession session, PortalSettings settings) {
        IntVector3 offset = settings.homeToDungeon().destinationOffset();
        IntVector3 destination = session.currentDungeonOrigin().add(offset);
        return new Location(session.world(), destination.x() + 0.5D, destination.y(), destination.z() + 0.5D);
    }

    private BlockBox portalFloor(Location center) {
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();
        return BlockBox.fromPoints(new IntVector3(x - 1, y, z - 1), new IntVector3(x + 1, y, z + 1));
    }

    private BlockBox portalTriggerArea(Location center) {
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();
        return BlockBox.fromPoints(new IntVector3(x - 1, y, z - 1), new IntVector3(x + 1, y + 2, z + 1));
    }

    private void placePortalBlocks(World world, BlockBox area, Material material, List<BlockState> originalBlocks) {
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    originalBlocks.add(block.getState());
                    block.setType(material, false);
                }
            }
        }
    }

    private boolean contains(BlockBox area, Location location) {
        return location.getWorld() != null
                && area.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
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
            List<BlockState> originalBlocks
    ) {
    }
}
