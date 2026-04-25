package net.azisaba.aziRouge.game;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.GenerationSettings;
import net.azisaba.aziRouge.dungeon.DungeonGenerationResult;
import net.azisaba.aziRouge.dungeon.GenerationExecutionRequest;
import net.azisaba.aziRouge.dungeon.PlacedPiece;
import net.azisaba.aziRouge.entity.MobSpawnManager;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import net.azisaba.aziRouge.schematic.SchematicPlacementException;
import net.azisaba.aziRouge.template.TemplateLoadException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

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
    private final SessionWorldService sessionWorldService;
    private final Map<String, GameSession> sessionsById = new HashMap<>();
    private final Map<UUID, GameSession> sessionsByWorld = new HashMap<>();
    private final Map<UUID, String> playerToSessionId = new HashMap<>();
    private final Map<UUID, PlayerAttributeSnapshot> playerAttributeSnapshots = new HashMap<>();
    private final Map<UUID, GameMode> playerGameModeSnapshots = new HashMap<>();
    private final Map<UUID, PlayerVitalsSnapshot> playerVitalsSnapshots = new HashMap<>();

    public GameSessionManager(AziRouge plugin, MobSpawnManager mobSpawnManager) {
        this.plugin = plugin;
        this.mobSpawnManager = mobSpawnManager;
        this.sessionWorldService = new SessionWorldService(plugin);
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

    public void cleanupLeftoverWorldFoldersOnStartup() {
        sessionWorldService.cleanupLeftoverWorldFoldersOnStartup();
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
        Path worldFolder = sessionWorldService.sessionWorldFolder(worldName);
        sessionWorldService.copyTemplateWorld(worldFolder, worldName);
        World world = Bukkit.createWorld(new WorldCreator(worldName));
        if (world == null) {
            sessionWorldService.deleteWorldFolder(worldFolder, worldName);
            throw new IllegalStateException("Failed to create session world " + worldName + ".");
        }
        world.setAutoSave(false);

        try {
            GameSession session = new GameSession(
                    sessionId,
                    player.getUniqueId(),
                    world,
                    maxPlayers,
                    homeSpawn(world),
                    homeReturnSpawn(world),
                    plugin.settings().home().area(),
                    List.of(),
                    plugin.settings().economy().initialBalance()
            );
            session.setMaxDepth(plugin.settings().dungeon().defaultMaxDepth());
            session.setSelectedPreset("default");
            registerSession(session);
            addPlayerToSession(session, player, player.getLocation());

            if (!player.teleport(session.spawnLocation())) {
                unregisterSession(session);
                restorePlayerAttributes(player);
                restorePlayerVitals(player);
                throw new IllegalStateException("Failed to teleport player into session world.");
            }

            initializeSessionPlayerState(player);
            BukkitTask mobSpawnTask = mobSpawnManager.start(session);
            session.setMobSpawnTask(mobSpawnTask);
            return session;
        } catch (RuntimeException ex) {
            GameSession registered = sessionsByWorld.get(world.getUID());
            if (registered != null) {
                unregisterSession(registered);
            }
            restorePlayerAttributes(player);
            restorePlayerVitals(player);
            sessionWorldService.cleanupWorld(world);
            throw ex;
        }
    }

    public GameSession joinSession(Player player, String sessionId) {
        GameSession session = sessionById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (!canJoinSession(session)) {
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
            restorePlayerVitals(player);
            scheduleIdleTimeoutIfNeeded(session);
            throw new IllegalStateException("Failed to teleport into session " + session.sessionId() + ".");
        }
        initializeSessionPlayerState(player);
        if (session.state() == SessionState.IN_ROUND) {
            session.markPendingNextRound(player.getUniqueId());
            makeRoundSpectatorAtHome(session, player);
        } else {
            restoreGameMode(player);
        }
        return session;
    }

    public GameSession leaveSession(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("You are not in an active session."));
        if (session.state() == SessionState.CLOSING) {
            throw new IllegalStateException("Cannot leave session " + session.sessionId() + " while it is " + session.state() + ".");
        }

        boolean wasAlive = session.alivePlayers().contains(player.getUniqueId());
        boolean inSessionWorld = player.getWorld().getUID().equals(session.world().getUID());
        if (inSessionWorld) {
            Location returnLocation = resolveReturnLocation(session, player.getUniqueId());
            boolean teleported = returnLocation != null && player.teleport(returnLocation);
            if (!teleported) {
                throw new IllegalStateException("Failed to teleport out of session " + session.sessionId() + ".");
            }
        }

        restorePlayerAttributes(player);
        restoreGameMode(player);
        restorePlayerVitals(player);
        if (wasAlive) {
            session.markDead(player.getUniqueId());
        }
        removePlayerFromSession(session, player.getUniqueId());
        if (wasAlive) {
            updateRoundAfterAliveChange(session);
        }
        scheduleIdleTimeoutIfNeeded(session);
        return session;
    }

    public DungeonGenerationResult startRound(
            GameSession session,
            List<String> templatePatterns,
            String startPieceId,
            String preset,
            int maxDepth
    ) throws TemplateLoadException, SchematicPlacementException {
        if (!Bukkit.isPrimaryThread()) {
            try {
                return Bukkit.getScheduler().callSyncMethod(
                        plugin,
                        () -> startRound(session, templatePatterns, startPieceId, preset, maxDepth)
                ).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting round.", ex);
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
                throw new IllegalStateException("Failed to start round.", cause);
            }
        }

        if (!sessionsById.containsKey(session.sessionId())) {
            throw new IllegalStateException("Session is not active.");
        }
        if (session.state() != SessionState.LOBBY && session.state() != SessionState.BETWEEN_ROUNDS) {
            throw new IllegalStateException("Round can only be started from LOBBY or BETWEEN_ROUNDS.");
        }

        List<UUID> participants = session.onlineMembers().stream()
                .filter(playerId -> Bukkit.getPlayer(playerId) != null)
                .toList();
        if (participants.isEmpty()) {
            throw new IllegalStateException("No online members are available to start the round.");
        }

        restoreRoundInactivePlayersForNextRound(session);

        SessionState previousState = session.state();
        RoundState previousRoundState = session.roundState();
        int previousRound = session.currentRound();
        IntVector3 previousOrigin = session.currentDungeonOrigin();
        BlockBox previousBounds = session.currentDungeonBounds();
        List<PlacedPiece> previousPieces = session.placedPieces();

        session.setState(SessionState.BETWEEN_ROUNDS);
        session.setRoundState(RoundState.PREPARING);
        session.setCurrentRound(previousRound + 1);
        IntVector3 origin = allocateDungeonOrigin(session);
        session.setCurrentDungeonOrigin(origin);
        try {
            DungeonGenerationResult result = plugin.dungeonGenerator().generate(
                    new GenerationExecutionRequest(
                            List.copyOf(templatePatterns),
                            startPieceId,
                            session.world(),
                            origin,
                            ThreadLocalRandom.current().nextLong(),
                            maxDepth
                    ),
                    plugin.settings()
            );

            session.setPlacedPieces(result.placedPieces());
            session.setCurrentDungeonBounds(resolveDungeonBounds(result.placedPieces(), origin));
            session.setSelectedPreset(preset);
            session.setMaxDepth(maxDepth);
            session.setActiveParticipants(new java.util.HashSet<>(participants));
            session.setRoundState(RoundState.ACTIVE);
            session.setState(SessionState.IN_ROUND);
            plugin.portalService().installRoundPortals(session);

            for (UUID playerId : participants) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    session.markDead(playerId);
                    continue;
                }
                setRoundSurvival(player);
                initializeSessionPlayerState(player);
            }
            updateRoundAfterAliveChange(session);
            return result;
        } catch (TemplateLoadException | SchematicPlacementException | RuntimeException ex) {
            session.setState(previousState);
            session.setRoundState(previousRoundState);
            session.setCurrentRound(previousRound);
            session.setCurrentDungeonOrigin(previousOrigin);
            session.setCurrentDungeonBounds(previousBounds);
            session.setPlacedPieces(previousPieces);
            plugin.portalService().clearRoundPortals(session);
            throw ex;
        }
    }

    public RoundEndResult endRound(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("You are not in an active session."));
        if (session.state() != SessionState.IN_ROUND) {
            throw new IllegalStateException("There is no active round in this session.");
        }
        if (!isInHomeArea(session, player.getLocation())) {
            throw new IllegalStateException("Round end can only be run from the home area.");
        }

        session.setRoundState(RoundState.ENDING);
        for (UUID playerId : List.copyOf(session.alivePlayers())) {
            Player alivePlayer = Bukkit.getPlayer(playerId);
            if (alivePlayer != null && session.currentDungeonBounds() != null
                    && session.currentDungeonBounds().contains(
                    alivePlayer.getLocation().getBlockX(),
                    alivePlayer.getLocation().getBlockY(),
                    alivePlayer.getLocation().getBlockZ())) {
                session.markDead(playerId);
            }
        }
        EconomyService.SellResult sellResult = plugin.economyService().sellInventoryLoot(session);
        moveOnlineMembersHome(session);
        session.clearAlivePlayers();
        EconomyService.MaintenancePaymentResult maintenanceResult =
                plugin.economyService().chargeMaintenanceForRound(session, session.currentRound());
        if (maintenanceResult.paid()) {
            session.setRoundState(RoundState.ENDED);
            session.setState(SessionState.BETWEEN_ROUNDS);
        }
        plugin.portalService().clearRoundPortals(session);
        return new RoundEndResult(sellResult, maintenanceResult);
    }

    public record RoundEndResult(
            EconomyService.SellResult sellResult,
            EconomyService.MaintenancePaymentResult maintenanceResult
    ) {
        public long totalAmount() {
            return sellResult.totalAmount();
        }

        public int itemCount() {
            return sellResult.itemCount();
        }

        public long maintenanceCost() {
            return maintenanceResult.cost();
        }

        public boolean maintenancePaid() {
            return maintenanceResult.paid();
        }
    }

    public void selectDungeon(GameSession session, String preset, int maxDepth) {
        if (session.state() == SessionState.CLOSING) {
            throw new IllegalStateException("Cannot select dungeon for a closing session.");
        }
        session.setSelectedPreset(preset);
        session.setMaxDepth(maxDepth);
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
        plugin.portalService().clearRoundPortals(session);
        evacuatePlayers(session);
        restoreOnlineMembers(session);

        if (!session.world().getPlayers().isEmpty()) {
            plugin.getLogger().warning("Session world still has players after evacuation: " + session.world().getName());
        }

        if (!Bukkit.unloadWorld(session.world(), false)) {
            plugin.getLogger().severe("Failed to unload session world: " + session.world().getName());
            return false;
        }

        sessionWorldService.deleteWorldFolder(worldFolder.toPath(), session.world().getName());
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
            initializeSessionPlayerState(player);
            if (targetSession.state() == SessionState.IN_ROUND && !targetSession.alivePlayers().contains(player.getUniqueId())) {
                targetSession.markPendingNextRound(player.getUniqueId());
                makeRoundSpectatorAtHome(targetSession, player);
            }
            return;
        }

        restorePlayerAttributes(player);
        restoreGameMode(player);
        restorePlayerVitals(player);
    }

    public void handlePlayerJoin(Player player) {
        GameSession session = sessionsByWorld.get(player.getWorld().getUID());
        if (session == null) {
            GameSession associatedSession = sessionForPlayer(player.getUniqueId()).orElse(null);
            if (associatedSession != null) {
                associatedSession.markOnline(player.getUniqueId());
                cancelIdleTimeout(associatedSession);
                if (associatedSession.state() == SessionState.IN_ROUND) {
                    associatedSession.markPendingNextRound(player.getUniqueId());
                    makeRoundSpectatorAtHome(associatedSession, player);
                } else {
                    restoreGameMode(player);
                    initializeSessionPlayerState(player);
                    player.teleport(associatedSession.spawnLocation());
                }
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
        initializeSessionPlayerState(player);
        if (session.state() == SessionState.IN_ROUND && !session.alivePlayers().contains(player.getUniqueId())) {
            session.markPendingNextRound(player.getUniqueId());
            makeRoundSpectatorAtHome(session, player);
        } else if (session.state() == SessionState.LOBBY || session.state() == SessionState.BETWEEN_ROUNDS) {
            restoreGameMode(player);
        }
    }

    public void handlePlayerQuit(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }

        boolean wasAlive = session.alivePlayers().contains(player.getUniqueId());
        session.markOffline(player.getUniqueId());
        restorePlayerAttributes(player);
        restoreGameMode(player);
        restorePlayerVitals(player);
        if (wasAlive) {
            session.markDead(player.getUniqueId());
            updateRoundAfterAliveChange(session);
        }
        scheduleIdleTimeoutIfNeeded(session);
    }

    public void handlePlayerDeath(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || session.state() != SessionState.IN_ROUND) {
            return;
        }
        session.markDead(player.getUniqueId());
        session.markPendingNextRound(player.getUniqueId());
        updateRoundAfterAliveChange(session);
    }

    public void handlePlayerRespawn(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || session.state() != SessionState.IN_ROUND || session.alivePlayers().contains(player.getUniqueId())) {
            return;
        }
        session.markPendingNextRound(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> makeRoundSpectatorAtHome(session, player));
    }

    public Location respawnLocationFor(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || session.state() != SessionState.IN_ROUND || session.alivePlayers().contains(player.getUniqueId())) {
            return null;
        }
        return session.spawnLocation();
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
            restoreGameMode(player);
            restorePlayerVitals(player);
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

    private boolean canJoinSession(GameSession session) {
        return session.state() != SessionState.CLOSING
                && session.state() != SessionState.GAME_OVER
                && session.roundState() != RoundState.PREPARING
                && session.roundState() != RoundState.ENDING;
    }

    private int validateMaxPlayers(int requestedMaxPlayers) {
        int maxAllowed = plugin.settings().sessions().maxMaxPlayers();
        if (requestedMaxPlayers > maxAllowed) {
            throw new IllegalArgumentException("maxPlayers must be " + maxAllowed + " or less.");
        }
        return Math.max(1, requestedMaxPlayers);
    }

    private IntVector3 allocateDungeonOrigin(GameSession session) {
        int roundIndex = Math.max(1, session.currentRound());
        int x = session.homeArea().maxX()
                + plugin.settings().dungeon().baseDistanceFromHome()
                + ((roundIndex - 1) * plugin.settings().dungeon().roundSpacing());
        return new IntVector3(x, plugin.settings().generation().origin().y(), session.homeArea().minZ());
    }

    private BlockBox resolveDungeonBounds(List<PlacedPiece> placedPieces, IntVector3 origin) {
        if (placedPieces.isEmpty()) {
            return new BlockBox(origin, origin);
        }

        BlockBox bounds = placedPieces.get(0).worldBounds();
        int minX = bounds.minX();
        int minY = bounds.minY();
        int minZ = bounds.minZ();
        int maxX = bounds.maxX();
        int maxY = bounds.maxY();
        int maxZ = bounds.maxZ();
        for (int index = 1; index < placedPieces.size(); index++) {
            BlockBox next = placedPieces.get(index).worldBounds();
            minX = Math.min(minX, next.minX());
            minY = Math.min(minY, next.minY());
            minZ = Math.min(minZ, next.minZ());
            maxX = Math.max(maxX, next.maxX());
            maxY = Math.max(maxY, next.maxY());
            maxZ = Math.max(maxZ, next.maxZ());
        }
        return new BlockBox(new IntVector3(minX, minY, minZ), new IntVector3(maxX, maxY, maxZ));
    }

    private boolean isInHomeArea(GameSession session, Location location) {
        return location.getWorld() != null
                && location.getWorld().getUID().equals(session.world().getUID())
                && session.homeArea().contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void moveOnlineMembersHome(GameSession session) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }
            setRoundSurvival(player);
            initializeSessionPlayerState(player);
            player.teleport(session.spawnLocation());
        }
    }

    private void updateRoundAfterAliveChange(GameSession session) {
        if (session.state() != SessionState.IN_ROUND || !session.alivePlayers().isEmpty()) {
            return;
        }

        moveOnlineMembersHome(session);
        plugin.portalService().clearRoundPortals(session);
        session.setRoundState(RoundState.ENDED);
        session.setState(SessionState.GAME_OVER);
    }

    private void setSpectator(Player player) {
        playerGameModeSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> player.getGameMode());
        player.setGameMode(GameMode.SPECTATOR);
    }

    private void restoreGameMode(Player player) {
        GameMode gameMode = playerGameModeSnapshots.remove(player.getUniqueId());
        if (gameMode != null) {
            player.setGameMode(gameMode);
        }
    }

    private void makeRoundSpectatorAtHome(GameSession session, Player player) {
        initializeSessionPlayerState(player);
        setSpectator(player);
        player.teleport(session.spawnLocation());
    }

    private void restoreRoundInactivePlayersForNextRound(GameSession session) {
        for (UUID playerId : session.deadPlayers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                setRoundSurvival(player);
                initializeSessionPlayerState(player);
                player.teleport(session.spawnLocation());
            }
        }
        for (UUID playerId : session.pendingPlayersNextRound()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                setRoundSurvival(player);
                initializeSessionPlayerState(player);
                player.teleport(session.spawnLocation());
            }
        }
    }

    private void initializeSessionPlayerState(Player player) {
        applyPlayerAttributesAndParams(player);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
    }

    private void setRoundSurvival(Player player) {
        playerGameModeSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> player.getGameMode());
        player.setGameMode(GameMode.SURVIVAL);
    }

    private void restorePlayerVitals(Player player) {
        PlayerVitalsSnapshot snapshot = playerVitalsSnapshots.remove(player.getUniqueId());
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (snapshot != null) {
            if (maxHealth != null) {
                try {
                    player.setHealth(Math.max(1.0D, Math.min(snapshot.health(), maxHealth.getValue())));
                } catch (IllegalArgumentException ignored) {
                    // A dead player may reject health updates until respawn; restore what we can.
                }
            }
            player.setFoodLevel(snapshot.foodLevel());
            player.setSaturation(snapshot.saturation());
            player.setExhaustion(snapshot.exhaustion());
            return;
        }

        if (maxHealth != null) {
            double restoredHealth = player.isDead()
                    ? maxHealth.getValue()
                    : Math.max(1.0D, Math.min(player.getHealth(), maxHealth.getValue()));
            try {
                player.setHealth(restoredHealth);
            } catch (IllegalArgumentException ignored) {
                // A dead player may reject health updates until respawn; restore what we can.
            }
        }
        player.setFoodLevel(20);
        player.setSaturation(5.0F);
        player.setExhaustion(0.0F);
    }

    private Location homeSpawn(World world) {
        IntVector3 spawn = plugin.settings().home().spawn();
        return new Location(world, spawn.x() + 0.5D, spawn.y(), spawn.z() + 0.5D);
    }

    private Location homeReturnSpawn(World world) {
        IntVector3 spawn = plugin.settings().home().returnSpawn();
        return new Location(world, spawn.x() + 0.5D, spawn.y(), spawn.z() + 0.5D);
    }

    private String allocateSessionId() {
        String worldNamePrefix = plugin.settings().sessions().worldNamePrefix();
        for (int attempts = 0; attempts < 16; attempts++) {
            String id = UUID.randomUUID().toString().substring(0, 6).toLowerCase();
            if (!sessionsById.containsKey(id) && Bukkit.getWorld(worldNamePrefix + id) == null) {
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
            restoreGameMode(player);
            restorePlayerVitals(player);
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
                restoreGameMode(player);
                restorePlayerVitals(player);
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
        playerVitalsSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> PlayerVitalsSnapshot.capture(player));
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

    private record PlayerVitalsSnapshot(double health, int foodLevel, float saturation, float exhaustion) {
        private static PlayerVitalsSnapshot capture(Player player) {
            return new PlayerVitalsSnapshot(
                    player.getHealth(),
                    player.getFoodLevel(),
                    player.getSaturation(),
                    player.getExhaustion()
            );
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
