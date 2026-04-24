package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.dungeon.PlacedPiece;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class GameSession {
    private final String sessionId;
    private final UUID owner;
    private final World world;
    private final int maxPlayers;
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> onlineMembers = new HashSet<>();
    private final Set<UUID> alivePlayers = new HashSet<>();
    private final Set<UUID> deadPlayers = new HashSet<>();
    private final Set<UUID> pendingPlayersNextRound = new HashSet<>();
    private final Map<UUID, Location> savedReturnLocations = new HashMap<>();
    private List<PlacedPiece> placedPieces;
    private final Location spawnLocation;
    private final BlockBox homeArea;
    private final Instant createdAt;
    private SessionState state = SessionState.LOBBY;
    private RoundState roundState = RoundState.ENDED;
    private int currentRound;
    private IntVector3 currentDungeonOrigin;
    private BlockBox currentDungeonBounds;
    private String selectedPreset = "default";
    private String selectedDifficulty;
    private BukkitTask mobSpawnTask;
    private BukkitTask idleTimeoutTask;

    public GameSession(
            String sessionId,
            UUID owner,
            World world,
            int maxPlayers,
            Location spawnLocation,
            BlockBox homeArea,
            List<PlacedPiece> placedPieces
    ) {
        this.sessionId = sessionId;
        this.owner = owner;
        this.world = world;
        this.maxPlayers = maxPlayers;
        this.spawnLocation = spawnLocation.clone();
        this.homeArea = homeArea;
        this.placedPieces = List.copyOf(placedPieces);
        this.createdAt = Instant.now();
    }

    public String sessionId() {
        return sessionId;
    }

    public UUID owner() {
        return owner;
    }

    public World world() {
        return world;
    }

    public SessionState state() {
        return state;
    }

    public void setState(SessionState state) {
        this.state = state;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public Set<UUID> onlineMembers() {
        return Collections.unmodifiableSet(onlineMembers);
    }

    public Set<UUID> alivePlayers() {
        return Collections.unmodifiableSet(alivePlayers);
    }

    public Set<UUID> deadPlayers() {
        return Collections.unmodifiableSet(deadPlayers);
    }

    public Set<UUID> pendingPlayersNextRound() {
        return Collections.unmodifiableSet(pendingPlayersNextRound);
    }

    public RoundState roundState() {
        return roundState;
    }

    public void setRoundState(RoundState roundState) {
        this.roundState = roundState;
    }

    public int currentRound() {
        return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = Math.max(0, currentRound);
    }

    public IntVector3 currentDungeonOrigin() {
        return currentDungeonOrigin;
    }

    public void setCurrentDungeonOrigin(IntVector3 currentDungeonOrigin) {
        this.currentDungeonOrigin = currentDungeonOrigin;
    }

    public BlockBox currentDungeonBounds() {
        return currentDungeonBounds;
    }

    public void setCurrentDungeonBounds(BlockBox currentDungeonBounds) {
        this.currentDungeonBounds = currentDungeonBounds;
    }

    public String selectedPreset() {
        return selectedPreset;
    }

    public void setSelectedPreset(String selectedPreset) {
        this.selectedPreset = selectedPreset;
    }

    public String selectedDifficulty() {
        return selectedDifficulty;
    }

    public void setSelectedDifficulty(String selectedDifficulty) {
        this.selectedDifficulty = selectedDifficulty;
    }

    public Map<UUID, Location> savedLocations() {
        return savedReturnLocations();
    }

    public Map<UUID, Location> savedReturnLocations() {
        return Collections.unmodifiableMap(savedReturnLocations);
    }

    public BukkitTask mobSpawnTask() {
        return mobSpawnTask;
    }

    public BukkitTask idleTimeoutTask() {
        return idleTimeoutTask;
    }

    public Instant startedAt() {
        return createdAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Location spawnLocation() {
        return spawnLocation.clone();
    }

    public BlockBox homeArea() {
        return homeArea;
    }

    public List<PlacedPiece> placedPieces() {
        return placedPieces;
    }

    public void setPlacedPieces(List<PlacedPiece> placedPieces) {
        this.placedPieces = List.copyOf(placedPieces);
    }

    public void setMobSpawnTask(BukkitTask mobSpawnTask) {
        this.mobSpawnTask = mobSpawnTask;
    }

    public void setIdleTimeoutTask(BukkitTask idleTimeoutTask) {
        this.idleTimeoutTask = idleTimeoutTask;
    }

    public boolean isMember(UUID playerId) {
        return members.contains(playerId);
    }

    public boolean isOnlineMember(UUID playerId) {
        return onlineMembers.contains(playerId);
    }

    public boolean addMember(UUID playerId) {
        return members.add(playerId);
    }

    public void removeMember(UUID playerId) {
        members.remove(playerId);
        onlineMembers.remove(playerId);
        alivePlayers.remove(playerId);
        deadPlayers.remove(playerId);
        pendingPlayersNextRound.remove(playerId);
        savedReturnLocations.remove(playerId);
    }

    public void markOnline(UUID playerId) {
        if (members.contains(playerId)) {
            onlineMembers.add(playerId);
        }
    }

    public void markOffline(UUID playerId) {
        onlineMembers.remove(playerId);
    }

    public void setActiveParticipants(Set<UUID> playerIds) {
        alivePlayers.clear();
        alivePlayers.addAll(playerIds);
        deadPlayers.clear();
        pendingPlayersNextRound.clear();
    }

    public void markDead(UUID playerId) {
        if (alivePlayers.remove(playerId)) {
            deadPlayers.add(playerId);
        }
    }

    public void markPendingNextRound(UUID playerId) {
        if (!alivePlayers.contains(playerId)) {
            pendingPlayersNextRound.add(playerId);
        }
    }

    public void clearRoundPlayers() {
        alivePlayers.clear();
        deadPlayers.clear();
        pendingPlayersNextRound.clear();
    }

    public void clearAlivePlayers() {
        alivePlayers.clear();
    }

    public boolean isRoundInactivePlayer(UUID playerId) {
        return deadPlayers.contains(playerId) || pendingPlayersNextRound.contains(playerId);
    }

    public boolean hasRoomFor(UUID playerId) {
        return members.contains(playerId) || members.size() < maxPlayers;
    }

    public void saveLocation(UUID playerId, Location location) {
        if (location != null) {
            savedReturnLocations.put(playerId, location.clone());
        }
    }

    public Location savedLocation(UUID playerId) {
        Location location = savedReturnLocations.get(playerId);
        return location == null ? null : location.clone();
    }

    public void removeSavedLocation(UUID playerId) {
        savedReturnLocations.remove(playerId);
    }

    public PlacedPiece randomRoom(Random random) {
        return placedPieces.get(random.nextInt(placedPieces.size()));
    }

    public int resolveDepth(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        PlacedPiece nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PlacedPiece piece : placedPieces) {
            BlockBox bounds = piece.worldBounds();
            if (bounds.contains(x, y, z)) {
                return piece.depth();
            }

            double distance = distanceSquaredToBox(x, y, z, bounds);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = piece;
            }
        }
        return nearest == null ? 0 : nearest.depth();
    }

    private double distanceSquaredToBox(int x, int y, int z, BlockBox bounds) {
        int dx = axisDistance(x, bounds.minX(), bounds.maxX());
        int dy = axisDistance(y, bounds.minY(), bounds.maxY());
        int dz = axisDistance(z, bounds.minZ(), bounds.maxZ());
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private int axisDistance(int value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0;
    }
}
