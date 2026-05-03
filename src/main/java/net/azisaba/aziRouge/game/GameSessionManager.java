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
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

public final class GameSessionManager {
    private static final String PREFIX = ChatColor.GOLD + "[Azirouge] " + ChatColor.RESET;
    private static final Map<Attribute, Double> SESSION_ATTRIBUTE_VALUES = Map.of(
            Attribute.MAX_HEALTH, 20.0D,
            Attribute.MOVEMENT_SPEED, 0.1D,
            Attribute.ATTACK_SPEED, 2.0D,
            Attribute.ENTITY_INTERACTION_RANGE, 2.3D
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
    private final Map<UUID, Integer> spectatorTargetIndexes = new HashMap<>();
    private final Set<UUID> pendingSessionCreations = new HashSet<>();

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

    public boolean isActivePlaying(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        return session != null
                && session.state() == SessionState.IN_ROUND
                && session.alivePlayers().contains(player.getUniqueId())
                && player.getWorld().getUID().equals(session.world().getUID())
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    public void cleanupLeftoverWorldFoldersOnStartup() {
        sessionWorldService.cleanupLeftoverWorldFoldersOnStartup();
    }

    public GameSession startSession(Player player) throws TemplateLoadException, SchematicPlacementException {
        GenerationSettings generation = plugin.settings().generation();
        return startSession(player, generation.templatePatterns(), generation.startPieceId());
    }

    public CompletableFuture<GameSession> startSessionAsync(Player player) {
        GenerationSettings generation = plugin.settings().generation();
        return startSessionAsync(player, generation.templatePatterns(), generation.startPieceId());
    }

    public GameSession startSession(Player player, List<String> templatePatterns, String startPieceId)
            throws TemplateLoadException, SchematicPlacementException {
        return startSession(player, templatePatterns, startPieceId, plugin.settings().sessions().defaultMaxPlayers());
    }

    public CompletableFuture<GameSession> startSessionAsync(Player player, List<String> templatePatterns, String startPieceId) {
        return startSessionAsync(player, templatePatterns, startPieceId, plugin.settings().sessions().defaultMaxPlayers());
    }

    public CompletableFuture<GameSession> startSessionAsync(
            Player player,
            List<String> templatePatterns,
            String startPieceId,
            int requestedMaxPlayers
    ) {
        CompletableFuture<GameSession> future = new CompletableFuture<>();
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> startSessionAsync(player, templatePatterns, startPieceId, requestedMaxPlayers)
                    .whenComplete((session, ex) -> {
                        if (ex != null) {
                            future.completeExceptionally(ex);
                        } else {
                            future.complete(session);
                        }
                    }));
            return future;
        }

        SessionCreationPlan plan;
        try {
            plan = prepareSessionCreation(player, requestedMaxPlayers);
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
            return future;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                sessionWorldService.copyTemplateWorld(plan.templateWorldFolder(), plan.worldFolder(), plan.worldName());
            } catch (RuntimeException ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingSessionCreations.remove(plan.playerId());
                    future.completeExceptionally(ex);
                });
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(createSessionAfterTemplateCopy(player, plan));
                } catch (RuntimeException ex) {
                    future.completeExceptionally(ex);
                }
            });
        });

        return future;
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

        SessionCreationPlan plan = prepareSessionCreation(player, requestedMaxPlayers);
        try {
            sessionWorldService.copyTemplateWorld(plan.templateWorldFolder(), plan.worldFolder(), plan.worldName());
            return createSessionAfterTemplateCopy(player, plan);
        } catch (RuntimeException ex) {
            pendingSessionCreations.remove(plan.playerId());
            throw ex;
        }
    }

    private SessionCreationPlan prepareSessionCreation(Player player, int requestedMaxPlayers) {
        if (sessionForPlayer(player.getUniqueId()).isPresent()) {
            throw new IllegalStateException("You are already in another session. Leave it first.");
        }
        int maxPlayers = validateMaxPlayers(requestedMaxPlayers);
        if (!pendingSessionCreations.add(player.getUniqueId())) {
            throw new IllegalStateException("A session is already being created for you.");
        }

        String sessionId = allocateSessionId();
        String worldName = plugin.settings().sessions().worldNamePrefix() + sessionId;
        Path worldFolder = sessionWorldService.sessionWorldFolder(worldName);
        Path templateWorldFolder = plugin.settings().sessions().homeTemplateWorldPath().toAbsolutePath().normalize();
        return new SessionCreationPlan(player.getUniqueId(), sessionId, worldName, worldFolder, templateWorldFolder, maxPlayers);
    }

    private GameSession createSessionAfterTemplateCopy(Player player, SessionCreationPlan plan) {
        try {
            if (!player.isOnline()) {
                sessionWorldService.deleteWorldFolder(plan.worldFolder(), plan.worldName());
                throw new IllegalStateException("Player went offline while creating the session.");
            }
            if (sessionForPlayer(player.getUniqueId()).isPresent()) {
                sessionWorldService.deleteWorldFolder(plan.worldFolder(), plan.worldName());
                throw new IllegalStateException("You are already in another session. Leave it first.");
            }

            World world = Bukkit.createWorld(new WorldCreator(plan.worldName()));
            if (world == null) {
                sessionWorldService.deleteWorldFolder(plan.worldFolder(), plan.worldName());
                throw new IllegalStateException("Failed to create session world: " + plan.worldName());
            }
            world.setAutoSave(false);

            try {
                GameSession session = new GameSession(
                        plan.sessionId(),
                        player.getUniqueId(),
                        world,
                        plan.maxPlayers(),
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
                    throw new IllegalStateException("Failed to teleport to the session world.");
                }

                preparePlayerForSessionEntry(player);
                sendTitle(player, ChatColor.GOLD + "AziRouge", ChatColor.YELLOW + "Session " + session.sessionId() + " created", 10, 50, 10);
                sendMessage(player, ChatColor.GREEN + "Created session " + session.sessionId() + ". Invite players with /azirouge session join " + session.sessionId() + ".");
                sendCopyableSessionId(player, session);
                sendMessage(player, ChatColor.YELLOW + "Prepare at home, then start a round with /azirouge round start.");
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
        } finally {
            pendingSessionCreations.remove(plan.playerId());
        }
    }

    public GameSession joinSession(Player player, String sessionId) {
        GameSession session = sessionById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (!canJoinSession(session)) {
            throw new IllegalStateException("Cannot join session " + session.sessionId() + " in its current state: " + session.state());
        }
        if (sessionForPlayer(player.getUniqueId()).isPresent()) {
            throw new IllegalStateException("You are already in another session. Leave it first.");
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
            throw new IllegalStateException("Failed to teleport to session " + session.sessionId() + ".");
        }
        preparePlayerForSessionEntry(player);
        if (session.state() == SessionState.IN_ROUND) {
            session.markPendingNextRound(player.getUniqueId());
            makeRoundSpectatorAtHome(session, player);
        } else {
            restoreGameMode(player);
        }
        sendTitle(player, ChatColor.GOLD + "AziRouge", ChatColor.YELLOW + "Joined session " + session.sessionId(), 10, 50, 10);
        sendMessage(player, ChatColor.GREEN + "Joined session " + session.sessionId() + ".");
        if (session.state() == SessionState.IN_ROUND) {
            sendMessage(player, ChatColor.YELLOW + "A round is in progress, so you will spectate this round. You can play from the next round.");
        } else {
            broadcastSessionMessage(session, ChatColor.YELLOW + player.getName() + " joined the session. Players: "
                    + session.members().size() + "/" + session.maxPlayers());
        }
        return session;
    }

    public GameSession leaveSession(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("You are not in a session."));
        if (session.state() == SessionState.CLOSING) {
            throw new IllegalStateException("Cannot leave session " + session.sessionId() + " in its current state: " + session.state());
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
        GameOverItemSupport.remove(plugin, player);
        if (wasAlive) {
            session.markDead(player.getUniqueId());
        }
        removePlayerFromSession(session, player.getUniqueId());
        if (wasAlive) {
            updateRoundAfterAliveChange(session);
        }
        scheduleIdleTimeoutIfNeeded(session);
        sendMessage(player, ChatColor.YELLOW + "Left AziRouge session " + session.sessionId() + ".");
        broadcastSessionMessage(session, ChatColor.YELLOW + player.getName() + " left the session. Players: "
                + session.members().size() + "/" + session.maxPlayers());
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
            throw new IllegalStateException("The session is no longer active.");
        }
        if (session.state() != SessionState.LOBBY && session.state() != SessionState.BETWEEN_ROUNDS) {
            throw new IllegalStateException("Rounds can only be started in the lobby or between rounds.");
        }

        List<UUID> participants = session.onlineMembers().stream()
                .filter(playerId -> Bukkit.getPlayer(playerId) != null)
                .toList();
        if (participants.isEmpty()) {
            throw new IllegalStateException("There are no online members who can start the round.");
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
            announceRoundStart(session);

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
                .orElseThrow(() -> new IllegalStateException("You are not in a session."));
        if (session.state() != SessionState.IN_ROUND) {
            throw new IllegalStateException("No round is currently active in this session.");
        }
        if (!isInHomeArea(session, player.getLocation())) {
            throw new IllegalStateException("Rounds can only be ended inside the home area.");
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
        RoundEndResult result = new RoundEndResult(sellResult, maintenanceResult);
        announceRoundEnd(session, result);
        if (!maintenanceResult.paid()) {
            clearOnlineMemberInventories(session);
            announceGameOver(session, "The session could not pay maintenance.");
        }
        return result;
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
            throw new IllegalStateException("Cannot select a dungeon while the session is closing.");
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
                player.kickPlayer(PREFIX + ChatColor.RED + "You cannot enter this session world.");
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
            broadcastSessionMessage(session, ChatColor.RED + player.getName() + " disconnected during the round and is out for this round.");
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
        sendTitle(player, ChatColor.RED + "Down", ChatColor.GRAY + "Spectating until the next round", 10, 60, 20);
        sendMessage(player, ChatColor.RED + "You are out for this round. You will return at the start of the next round.");
        broadcastSessionMessage(session, ChatColor.RED + player.getName() + " died. Alive: " + session.alivePlayers().size());
        updateRoundAfterAliveChange(session);
    }

    public void handleSpectatorCompass(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || session.state() != SessionState.IN_ROUND || !session.isRoundInactivePlayer(player.getUniqueId())) {
            return;
        }
        Player target = selectSpectatorTarget(session, player, true);
        if (target == null) {
            sendMessage(player, ChatColor.YELLOW + "No alive players are available to spectate.");
            return;
        }
        player.setSpectatorTarget(target);
        sendMessage(player, ChatColor.YELLOW + "Now spectating " + target.getName() + ".");
    }

    public void ensureSpectatorTarget(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null || session.state() != SessionState.IN_ROUND || !session.isRoundInactivePlayer(player.getUniqueId())) {
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }
        Player current = player.getSpectatorTarget() instanceof Player target ? target : null;
        if (current != null && session.alivePlayers().contains(current.getUniqueId()) && current.isOnline()) {
            return;
        }
        Player target = selectSpectatorTarget(session, player, false);
        if (target != null) {
            player.setSpectatorTarget(target);
        }
    }

    public void handlePlayerRespawn(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }
        if (session.state() == SessionState.GAME_OVER) {
            Bukkit.getScheduler().runTask(plugin, () -> restoreGameOverPlayerAtHome(session, player));
            return;
        }
        if (session.state() != SessionState.IN_ROUND || session.alivePlayers().contains(player.getUniqueId())) {
            return;
        }
        session.markPendingNextRound(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> makeRoundSpectatorAtHome(session, player));
    }

    public Location respawnLocationFor(Player player) {
        GameSession session = sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            return null;
        }
        if (session.state() == SessionState.GAME_OVER) {
            return session.spawnLocation();
        }
        if (session.state() != SessionState.IN_ROUND || session.alivePlayers().contains(player.getUniqueId())) {
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
        for (UUID playerId : session.members()) {
            spectatorTargetIndexes.remove(playerId);
        }
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
            if (player.isDead()) {
                continue;
            }
            initializeSessionPlayerState(player);
            player.teleport(session.spawnLocation());
        }
    }

    private void updateRoundAfterAliveChange(GameSession session) {
        if (session.state() != SessionState.IN_ROUND || !session.alivePlayers().isEmpty()) {
            return;
        }

        moveOnlineMembersHome(session);
        clearOnlineMemberInventories(session);
        plugin.portalService().clearRoundPortals(session);
        session.setRoundState(RoundState.ENDED);
        session.setState(SessionState.GAME_OVER);
        announceGameOver(session, "All players are out.");
    }

    private void setSpectator(Player player) {
        playerGameModeSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> player.getGameMode());
        player.setGameMode(GameMode.SPECTATOR);
    }

    private void restoreGameMode(Player player) {
        GameMode gameMode = playerGameModeSnapshots.remove(player.getUniqueId());
        SpectatorItemSupport.remove(plugin, player);
        spectatorTargetIndexes.remove(player.getUniqueId());
        player.setSpectatorTarget(null);
        if (gameMode != null) {
            player.setGameMode(gameMode);
        }
    }

    private void makeRoundSpectatorAtHome(GameSession session, Player player) {
        initializeSessionPlayerState(player);
        setSpectator(player);
        SpectatorItemSupport.give(plugin, player);
        Player target = selectSpectatorTarget(session, player, false);
        if (target == null) {
            player.teleport(session.spawnLocation());
            sendMessage(player, ChatColor.YELLOW + "You are spectating at home. No alive players are available.");
        } else {
            player.setSpectatorTarget(target);
            sendMessage(player, ChatColor.YELLOW + "You are spectating " + target.getName() + ". Use the compass to switch targets.");
        }
    }

    private void restoreGameOverPlayerAtHome(GameSession session, Player player) {
        if (!session.isMember(player.getUniqueId()) || session.state() != SessionState.GAME_OVER) {
            return;
        }
        setRoundSurvival(player);
        initializeSessionPlayerState(player);
        player.teleport(session.spawnLocation());
        GameOverItemSupport.give(plugin, player);
        sendMessage(player, ChatColor.YELLOW + "Game over. You returned home. Leave this session before creating the next one.");
    }

    private void restoreRoundInactivePlayersForNextRound(GameSession session) {
        java.util.Set<UUID> restoredPlayers = new java.util.HashSet<>();
        for (UUID playerId : session.deadPlayers()) {
            restoredPlayers.add(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                setRoundSurvival(player);
                initializeSessionPlayerState(player);
                player.teleport(session.spawnLocation());
                sendTitle(player, ChatColor.GREEN + "Returned", ChatColor.YELLOW + "You can play this round", 10, 45, 10);
            }
        }
        for (UUID playerId : session.pendingPlayersNextRound()) {
            if (!restoredPlayers.add(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                setRoundSurvival(player);
                initializeSessionPlayerState(player);
                player.teleport(session.spawnLocation());
                sendTitle(player, ChatColor.GREEN + "Returned", ChatColor.YELLOW + "You can play this round", 10, 45, 10);
            }
        }
    }

    private void initializeSessionPlayerState(Player player) {
        applyPlayerAttributesAndParams(player);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            try {
                player.setHealth(maxHealth.getValue());
            } catch (IllegalArgumentException ignored) {
                // Dead players reject health changes until respawn. Respawn handling restores this state.
            }
        }
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
    }

    private void setRoundSurvival(Player player) {
        playerGameModeSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> player.getGameMode());
        SpectatorItemSupport.remove(plugin, player);
        spectatorTargetIndexes.remove(player.getUniqueId());
        player.setSpectatorTarget(null);
        player.setGameMode(GameMode.SURVIVAL);
    }

    private void preparePlayerForSessionEntry(Player player) {
        clearPlayerRuntimeSnapshots(player.getUniqueId());
        player.getInventory().clear();
        player.getEnderChest().clear();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        initializeSessionPlayerState(player);
    }

    private void clearOnlineMemberInventories(GameSession session) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.getInventory().clear();
                player.getEnderChest().clear();
            }
        }
    }

    private void clearPlayerRuntimeSnapshots(UUID playerId) {
        playerAttributeSnapshots.remove(playerId);
        playerGameModeSnapshots.remove(playerId);
        playerVitalsSnapshots.remove(playerId);
    }

    private void announceRoundStart(GameSession session) {
        long maintenance = plugin.economyService().maintenanceCostForRound(session.currentRound());
        broadcastTitle(
                session,
                ChatColor.GOLD + "Round " + session.currentRound(),
                ChatColor.YELLOW + "The portal is open",
                10,
                60,
                15
        );
        broadcastSessionMessage(session, ChatColor.GREEN + "Round " + session.currentRound()
                + " has started. Enter the dungeon through the home portal.");
        broadcastSessionMessage(session, ChatColor.YELLOW + "Maintenance due at round end: " + maintenance
                + " / Shared balance: " + session.sharedBalance());
    }

    private void announceRoundEnd(GameSession session, RoundEndResult result) {
        String title = result.maintenancePaid()
                ? ChatColor.GREEN + "Round Complete"
                : ChatColor.RED + "Round Ended";
        String subtitle = ChatColor.YELLOW + "Sold " + result.itemCount()
                + " items / +" + result.totalAmount()
                + " / Maintenance " + result.maintenanceCost();
        broadcastTitle(session, title, subtitle, 10, 70, 20);
        broadcastSessionMessage(session, ChatColor.GREEN + "Round " + session.currentRound()
                + " ended. Sold items: " + result.itemCount() + " / Earned: " + result.totalAmount());
        if (result.maintenancePaid()) {
            broadcastSessionMessage(session, ChatColor.YELLOW + "Paid maintenance " + result.maintenanceCost()
                    + ". Shared balance: " + session.sharedBalance());
            broadcastSessionMessage(session, ChatColor.YELLOW + "Prepare at home, then start the next round when ready.");
        } else {
            broadcastSessionMessage(session, ChatColor.RED + "Could not pay maintenance " + result.maintenanceCost()
                    + ". Shared balance: " + session.sharedBalance());
        }
    }

    private void announceGameOver(GameSession session, String reason) {
        giveGameOverItems(session);
        launchGameOverFireworks(session);
        broadcastTitle(
                session,
                ChatColor.DARK_RED + "Game Over",
                ChatColor.RED + reason + ChatColor.GRAY + " / Reached round " + session.currentRound(),
                10,
                100,
                30
        );
        broadcastSessionMessage(session, ChatColor.RED + "Game over: " + reason);
        broadcastSessionMessage(session, ChatColor.GOLD + "Reached round: " + session.currentRound());
        broadcastSessionMessage(session, ChatColor.YELLOW + "Leave this session: /azirouge session leave");
        broadcastSessionMessage(session, ChatColor.YELLOW + "Create the next session after leaving with /azirouge session create, or right-click the start menu.");
        broadcastSessionMessage(session, ChatColor.GRAY + "This session will be closed automatically soon.");
        scheduleGameOverShutdown(session);
    }

    private void scheduleGameOverShutdown(GameSession session) {
        if (session.idleTimeoutTask() != null) {
            return;
        }
        long delayTicks = Math.max(20L, plugin.settings().sessions().idleTimeoutSeconds() * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            session.setIdleTimeoutTask(null);
            if (sessionsById.containsKey(session.sessionId()) && session.state() == SessionState.GAME_OVER) {
                endSession(session);
            }
        }, delayTicks);
        session.setIdleTimeoutTask(task);
    }

    private void launchGameOverFireworks(GameSession session) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.getWorld().getUID().equals(session.world().getUID())) {
                continue;
            }
            Firework firework = player.getWorld().spawn(player.getLocation(), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(org.bukkit.FireworkEffect.builder()
                    .with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.RED, Color.ORANGE)
                    .withFade(Color.YELLOW)
                    .trail(true)
                    .flicker(true)
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
            Bukkit.getScheduler().runTaskLater(plugin, firework::detonate, 2L);
        }
    }

    private void giveGameOverItems(GameSession session) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                GameOverItemSupport.give(plugin, player);
            }
        }
    }

    private void broadcastTitle(GameSession session, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                sendTitle(player, title, subtitle, fadeIn, stay, fadeOut);
            }
        }
    }

    private void broadcastSessionMessage(GameSession session, String message) {
        for (UUID playerId : session.onlineMembers()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                sendMessage(player, message);
            }
        }
    }

    private void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    private void sendMessage(Player player, String message) {
        player.sendMessage(PREFIX + message);
    }

    private void sendMessage(Player player, Component message) {
        player.sendMessage(Component.text("[Azirouge] ", NamedTextColor.GOLD).append(message));
    }

    private void sendCopyableSessionId(Player player, GameSession session) {
        sendMessage(player, Component.text("Session ID: ", NamedTextColor.YELLOW)
                .append(Component.text(session.sessionId(), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(session.sessionId())))
                .append(Component.text(" (click to copy)", NamedTextColor.GRAY)));
    }

    private Player selectSpectatorTarget(GameSession session, Player spectator, boolean advance) {
        List<Player> candidates = session.alivePlayers().stream()
                .map(Bukkit::getPlayer)
                .filter(player -> player != null
                        && player.isOnline()
                        && !player.getUniqueId().equals(spectator.getUniqueId())
                        && player.getWorld().getUID().equals(session.world().getUID()))
                .sorted((left, right) -> left.getName().compareToIgnoreCase(right.getName()))
                .toList();
        if (candidates.isEmpty()) {
            spectatorTargetIndexes.remove(spectator.getUniqueId());
            return null;
        }
        int index = spectatorTargetIndexes.getOrDefault(spectator.getUniqueId(), 0);
        if (advance) {
            index++;
        }
        index = Math.floorMod(index, candidates.size());
        spectatorTargetIndexes.put(spectator.getUniqueId(), index);
        return candidates.get(index);
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
                player.kickPlayer(PREFIX + ChatColor.RED + "The session world is closing.");
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

        player.setFoodLevel(20);
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

    private record SessionCreationPlan(
            UUID playerId,
            String sessionId,
            String worldName,
            Path worldFolder,
            Path templateWorldFolder,
            int maxPlayers
    ) {
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
