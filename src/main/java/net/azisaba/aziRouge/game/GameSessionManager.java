package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.GenerationSettings;
import net.azisaba.aziRouge.dungeon.DungeonGenerationResult;
import net.azisaba.aziRouge.dungeon.GenerationExecutionRequest;
import net.azisaba.aziRouge.entity.MobSpawnManager;
import net.azisaba.aziRouge.schematic.SchematicPlacementException;
import net.azisaba.aziRouge.template.TemplateLoadException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public final class GameSessionManager {
    private static final String PREFIX = ChatColor.GOLD + "[Azirouge] " + ChatColor.RESET;
    private static final Map<Attribute, Double> SESSION_ATTRIBUTE_VALUES = Map.of(
            Attribute.MAX_HEALTH, 20.0D,
            Attribute.MOVEMENT_SPEED, 0.1D,
            Attribute.ATTACK_SPEED, 5.0D,
            Attribute.ENTITY_INTERACTION_RANGE, 2D
    );

    private final AziRouge plugin;
    private final MobSpawnManager mobSpawnManager;
    private final Map<String, GameSession> sessionsById = new HashMap<>();
    private final Map<UUID, GameSession> sessionsByWorld = new HashMap<>();
    private final Map<UUID, String> playerToSessionId = new HashMap<>();
    private final Map<UUID, PlayerAttributeSnapshot> playerAttributeSnapshots = new HashMap<>();

    public GameSessionManager(AziRouge plugin, MobSpawnManager mobSpawnManager) {
        this.plugin = plugin;
        this.mobSpawnManager = mobSpawnManager;
    }

    public Optional<GameSession> sessionForPlayer(UUID playerId) {
        String sessionId = playerToSessionId.get(playerId);
        if (sessionId == null) {
            return Optional.empty();
        }
        GameSession session = sessionsById.get(sessionId);
        if (session == null) {
            playerToSessionId.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public Optional<GameSession> sessionForWorld(World world) {
        return Optional.ofNullable(sessionsByWorld.get(world.getUID()));
    }

    public Optional<GameSession> sessionById(String sessionId) {
        return Optional.ofNullable(sessionsById.get(normalizeSessionId(sessionId)));
    }

    public Collection<GameSession> sessions() {
        return List.copyOf(sessionsById.values());
    }

    public GameSession startSession(Player player) throws TemplateLoadException, SchematicPlacementException {
        GenerationSettings generation = plugin.settings().generation();
        return startSession(player, generation.templatePatterns(), generation.startPieceId());
    }

    public GameSession startSession(Player player, List<String> templatePatterns, String startPieceId)
            throws TemplateLoadException, SchematicPlacementException {
        return startSession(player, templatePatterns, startPieceId, plugin.settings().sessions().defaultMaxPlayers());
    }

    public GameSession startSession(Player player, List<String> templatePatterns, String startPieceId, int requestedMaxPlayers)
            throws TemplateLoadException, SchematicPlacementException {
        if (!Bukkit.isPrimaryThread()) {
            try {
                return Bukkit.getScheduler().callSyncMethod(
                        plugin,
                        () -> startSession(player, templatePatterns, startPieceId, requestedMaxPlayers)
                ).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting session.", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof TemplateLoadException templateLoadException) {
                    throw templateLoadException;
                }
                if (cause instanceof SchematicPlacementException schematicPlacementException) {
                    throw schematicPlacementException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Failed to start session.", cause);
            }
        }

        if (sessionForPlayer(player.getUniqueId()).isPresent()) {
            throw new IllegalStateException("You are already associated with an active session. Leave it first.");
        }

        int maxPlayers = validateMaxPlayers(requestedMaxPlayers);
        String sessionId = allocateSessionId();
        String worldName = plugin.settings().sessions().worldNamePrefix() + sessionId;
        WorldCreator creator = new WorldCreator(worldName)
                .type(WorldType.FLAT)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}")
                .generateStructures(false);
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            throw new IllegalStateException("Failed to create session world " + worldName + ".");
        }
        world.setAutoSave(false);

        try {
            GenerationSettings generation = plugin.settings().generation();
            DungeonGenerationResult result = plugin.dungeonGenerator().generate(
                    new GenerationExecutionRequest(
                            List.copyOf(templatePatterns),
                            startPieceId,
                            world,
                            generation.origin(),
                            ThreadLocalRandom.current().nextLong(),
                            null
                    ),
                    plugin.settings()
            );

            GameSession session = new GameSession(
                    sessionId,
                    player.getUniqueId(),
                    world,
                    maxPlayers,
                    result.spawnLocation(),
                    result.placedPieces()
            );
            registerSession(session);
            addPlayerToSession(session, player, player.getLocation());

            if (!player.teleport(result.spawnLocation().clone())) {
                unregisterSession(session);
                restorePlayerAttributes(player);
                throw new IllegalStateException("Failed to teleport player into session world.");
            }

            applyPlayerAttributesAndParams(player);
            BukkitTask mobSpawnTask = mobSpawnManager.start(session);
            session.setMobSpawnTask(mobSpawnTask);
            return session;
        } catch (TemplateLoadException | SchematicPlacementException | RuntimeException ex) {
            GameSession registered = sessionsByWorld.get(world.getUID());
            if (registered != null) {
                unregisterSession(registered);
            }
            restorePlayerAttributes(player);
            cleanupWorld(world);
            throw ex;
        }
    }

    public GameSession joinSession(Player player, String sessionId) {
        GameSession session = sessionById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (!canChangeMembership(session)) {
            throw new IllegalStateException("Cannot join session " + session.sessionId() + " while it is " + session.state() + ".");
        }
        if (sessionForPlayer(player.getUniqueId()).isPresent()) {
            throw new IllegalStateException("You are already associated with an active session. Leave it first.");
        }
        if (!session.hasRoomFor(player.getUniqueId())) {
            throw new IllegalStateException("Session " + session.sessionId() + " is full.");
        }

        Location returnLocation = player.getLocation();
        addPlayerToSession(session, player, returnLocation);
        cancelIdleTimeout(session);
        if (!player.teleport(session.spawnLocation())) {
            removePlayerFromSession(session, player.getUniqueId());
            restorePlayerAttributes(player);
            scheduleIdleTimeoutIfNeeded(session);
            throw new IllegalStateException("Failed to teleport into session " + session.sessionId() + ".");
        }
        applyPlayerAttributesAndParams(player);
        return session;
    }

    public GameSession leaveSession(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("You are not in an active session."));
        if (!canChangeMembership(session)) {
            throw new IllegalStateException("Cannot leave session " + session.sessionId() + " while it is " + session.state() + ".");
        }

        boolean inSessionWorld = player.getWorld().getUID().equals(session.world().getUID());
        if (inSessionWorld) {
            Location returnLocation = resolveReturnLocation(session, player.getUniqueId());
            boolean teleported = returnLocation != null && player.teleport(returnLocation);
            if (!teleported) {
                throw new IllegalStateException("Failed to teleport out of session " + session.sessionId() + ".");
            }
        }

        restorePlayerAttributes(player);
        removePlayerFromSession(session, player.getUniqueId());
        scheduleIdleTimeoutIfNeeded(session);
        return session;
    }

    public boolean endSession(GameSession session) {
        if (!Bukkit.isPrimaryThread()) {
            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () -> endSession(session)).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException ex) {
                plugin.getLogger().severe("Failed to end session: " + ex.getCause().getMessage());
                return false;
            }
        }

        if (!sessionsById.containsKey(session.sessionId())) {
            return false;
        }

        session.setState(SessionState.CLOSING);
        UUID worldId = session.world().getUID();
        File worldFolder = session.world().getWorldFolder();

        cancelMobTask(session);
        cancelIdleTimeout(session);
        evacuatePlayers(session);
        restoreOnlineMembers(session);

        if (!session.world().getPlayers().isEmpty()) {
            plugin.getLogger().warning("Session world still has players after evacuation: " + session.world().getName());
        }

        if (!Bukkit.unloadWorld(session.world(), false)) {
            plugin.getLogger().severe("Failed to unload session world: " + session.world().getName());
            return false;
        }

        deleteWorldFolder(worldFolder.toPath(), session.world().getName());
        sessionsById.remove(session.sessionId());
        sessionsByWorld.remove(worldId);
        clearPlayerMappings(session.sessionId());
        return true;
    }

    public boolean isSessionWorldEntryAllowed(Player player, World destinationWorld) {
        GameSession targetSession = sessionsByWorld.get(destinationWorld.getUID());
        return targetSession == null
                || (targetSession.state() != SessionState.CLOSING && targetSession.isMember(player.getUniqueId()));
    }

    public Location fallbackLocation(GameSession excludedSession) {
        for (World world : Bukkit.getWorlds()) {
            if (excludedSession != null && world.getUID().equals(excludedSession.world().getUID())) {
                continue;
            }
            if (sessionForWorld(world).isPresent()) {
                continue;
            }
            return world.getSpawnLocation().clone();
        }
        for (World world : Bukkit.getWorlds()) {
            if (excludedSession == null || !world.getUID().equals(excludedSession.world().getUID())) {
                return world.getSpawnLocation().clone();
            }
        }
        return null;
    }

    public void handlePlayerWorldChange(Player player, Location from, Location to) {
        World sourceWorld = from.getWorld();
        World destinationWorld = to.getWorld();
        GameSession sourceSession = sourceWorld == null ? null : sessionsByWorld.get(sourceWorld.getUID());
        GameSession targetSession = destinationWorld == null ? null : sessionsByWorld.get(destinationWorld.getUID());

        if (sourceSession == null && targetSession == null) {
            return;
        }
        if (sourceSession != null && targetSession != null && sourceSession.world().getUID().equals(targetSession.world().getUID())) {
            return;
        }

        if (targetSession != null) {
            if (!targetSession.isMember(player.getUniqueId()) || targetSession.state() == SessionState.CLOSING) {
                return;
            }
            capturePlayerJoin(player, from, targetSession);
            applyPlayerAttributesAndParams(player);
            return;
        }

        restorePlayerAttributes(player);
    }

    public void handlePlayerJoin(Player player) {
        GameSession session = sessionsByWorld.get(player.getWorld().getUID());
        if (session == null) {
            GameSession associatedSession = sessionForPlayer(player.getUniqueId()).orElse(null);
            if (associatedSession != null) {
                associatedSession.markOnline(player.getUniqueId());
                cancelIdleTimeout(associatedSession);
            }
            return;
        }

        if (!session.isMember(player.getUniqueId()) || session.state() == SessionState.CLOSING) {
            Location fallback = fallbackLocation(session);
            if (fallback != null) {
                player.teleport(fallback);
            } else {
                player.kickPlayer(PREFIX + ChatColor.RED + "Session world is unavailable.");
            }
            return;
        }

        playerToSessionId.put(player.getUniqueId(), session.sessionId());
        session.markOnline(player.getUniqueId());
        cancelIdleTimeout(session);
        applyPlayerAttributesAndParams(player);
    }

    public void handlePlayerQuit(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }

        session.markOffline(player.getUniqueId());
        restorePlayerAttributes(player);
        scheduleIdleTimeoutIfNeeded(session);
    }

    public int resolveDepth(World world, Location location) {
        GameSession session = sessionsByWorld.get(world.getUID());
        return session == null ? 0 : session.resolveDepth(location);
    }

    public void shutdown() {
        for (GameSession session : new ArrayList<>(sessionsById.values())) {
            endSession(session);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            restorePlayerAttributes(player);
        }
    }

    private void registerSession(GameSession session) {
        sessionsById.put(session.sessionId(), session);
        sessionsByWorld.put(session.world().getUID(), session);
    }

    private void unregisterSession(GameSession session) {
        sessionsById.remove(session.sessionId());
        sessionsByWorld.remove(session.world().getUID());
        clearPlayerMappings(session.sessionId());
    }

    private void addPlayerToSession(GameSession session, Player player, Location returnLocation) {
        UUID playerId = player.getUniqueId();
        session.addMember(playerId);
        session.markOnline(playerId);
        if (session.savedLocation(playerId) == null) {
            session.saveLocation(playerId, returnLocation);
        }
        playerToSessionId.put(playerId, session.sessionId());
    }

    private void removePlayerFromSession(GameSession session, UUID playerId) {
        session.removeMember(playerId);
        playerToSessionId.remove(playerId);
    }

    private void capturePlayerJoin(Player player, Location previousLocation, GameSession targetSession) {
        UUID playerId = player.getUniqueId();
        if (targetSession.savedLocation(playerId) == null && previousLocation.getWorld() != null
                && !previousLocation.getWorld().getUID().equals(targetSession.world().getUID())) {
            targetSession.saveLocation(playerId, previousLocation);
        }
        playerToSessionId.put(playerId, targetSession.sessionId());
        targetSession.markOnline(playerId);
        cancelIdleTimeout(targetSession);
    }

    private boolean canChangeMembership(GameSession session) {
        return session.state() != SessionState.IN_ROUND && session.state() != SessionState.CLOSING;
    }

    private int validateMaxPlayers(int requestedMaxPlayers) {
        int maxAllowed = plugin.settings().sessions().maxMaxPlayers();
        if (requestedMaxPlayers > maxAllowed) {
            throw new IllegalArgumentException("maxPlayers must be " + maxAllowed + " or less.");
        }
        return Math.max(1, requestedMaxPlayers);
    }

    private String allocateSessionId() {
        for (int attempts = 0; attempts < 16; attempts++) {
            String id = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
            if (!sessionsById.containsKey(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to allocate a unique session id.");
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null ? "" : sessionId.toLowerCase(Locale.ROOT);
    }

    private void scheduleIdleTimeoutIfNeeded(GameSession session) {
        if (!sessionsById.containsKey(session.sessionId()) || !canChangeMembership(session) || !session.onlineMembers().isEmpty()) {
            return;
        }
        if (session.idleTimeoutTask() != null) {
            return;
        }

        long delayTicks = plugin.settings().sessions().idleTimeoutSeconds() * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            session.setIdleTimeoutTask(null);
            if (sessionsById.containsKey(session.sessionId()) && canChangeMembership(session) && session.onlineMembers().isEmpty()) {
                endSession(session);
            }
        }, delayTicks);
        session.setIdleTimeoutTask(task);
    }

    private void cancelIdleTimeout(GameSession session) {
        if (session.idleTimeoutTask() != null) {
            session.idleTimeoutTask().cancel();
            session.setIdleTimeoutTask(null);
        }
    }

    private void evacuatePlayers(GameSession session) {
        List<Player> playersInWorld = new ArrayList<>(session.world().getPlayers());
        for (Player player : playersInWorld) {
            Location returnLocation = resolveReturnLocation(session, player.getUniqueId());
            boolean teleported = returnLocation != null && player.teleport(returnLocation);
            restorePlayerAttributes(player);
            if (!teleported) {
                player.kickPlayer(PREFIX + ChatColor.RED + "Session world is shutting down.");
            }
        }
    }

    private void restoreOnlineMembers(GameSession session) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                restorePlayerAttributes(player);
            }
        }
    }

    private Location resolveReturnLocation(GameSession session, UUID playerId) {
        Location savedLocation = session.savedLocation(playerId);
        if (savedLocation != null
                && savedLocation.getWorld() != null
                && !savedLocation.getWorld().getUID().equals(session.world().getUID())
                && sessionForWorld(savedLocation.getWorld()).isEmpty()) {
            return savedLocation;
        }

        return fallbackLocation(session);
    }

    private void clearPlayerMappings(String sessionId) {
        playerToSessionId.entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
    }

    private void cancelMobTask(GameSession session) {
        if (session.mobSpawnTask() != null) {
            session.mobSpawnTask().cancel();
            session.setMobSpawnTask(null);
        }
    }

    private void applyPlayerAttributesAndParams(Player player) {
        playerAttributeSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> PlayerAttributeSnapshot.capture(player));
        for (Map.Entry<Attribute, Double> entry : SESSION_ATTRIBUTE_VALUES.entrySet()) {
            AttributeInstance attributeInstance = player.getAttribute(entry.getKey());
            if (attributeInstance != null) {
                attributeInstance.setBaseValue(entry.getValue());
            }
        }

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }

        player.setFoodLevel(0);
    }

    private void restorePlayerAttributes(Player player) {
        PlayerAttributeSnapshot snapshot = playerAttributeSnapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }

        snapshot.restore(player);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }

        player.setFoodLevel(20);
    }

    private void cleanupWorld(World world) {
        File worldFolder = world.getWorldFolder();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().severe("Failed to unload temporary session world " + world.getName() + " after startup error.");
            return;
        }
        deleteWorldFolder(worldFolder.toPath(), world.getName());
    }

    private void deleteWorldFolder(Path root, String worldName) {
        if (!Files.exists(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to delete session world folder for " + worldName + ": " + ex.getMessage());
        }
    }

    private record PlayerAttributeSnapshot(Map<Attribute, Double> baseValues) {
        private static PlayerAttributeSnapshot capture(Player player) {
            Map<Attribute, Double> baseValues = new HashMap<>();
            for (Attribute attribute : SESSION_ATTRIBUTE_VALUES.keySet()) {
                AttributeInstance attributeInstance = player.getAttribute(attribute);
                if (attributeInstance != null) {
                    baseValues.put(attribute, attributeInstance.getBaseValue());
                }
            }
            return new PlayerAttributeSnapshot(Map.copyOf(baseValues));
        }

        private void restore(Player player) {
            for (Map.Entry<Attribute, Double> entry : baseValues.entrySet()) {
                AttributeInstance attributeInstance = player.getAttribute(entry.getKey());
                if (attributeInstance != null) {
                    attributeInstance.setBaseValue(entry.getValue());
                }
            }
        }
    }
}
