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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
            Attribute.MOVEMENT_SPEED, 0.11D,
            Attribute.ATTACK_SPEED, 5.0D,
            Attribute.ENTITY_INTERACTION_RANGE, 1D
    );

    private final AziRouge plugin;
    private final MobSpawnManager mobSpawnManager;
    private final Map<UUID, GameSession> sessionsByWorld = new HashMap<>();
    private final Map<UUID, UUID> playerToSessionWorld = new HashMap<>();
    private final Map<UUID, PlayerAttributeSnapshot> playerAttributeSnapshots = new HashMap<>();

    public GameSessionManager(AziRouge plugin, MobSpawnManager mobSpawnManager) {
        this.plugin = plugin;
        this.mobSpawnManager = mobSpawnManager;
    }

    public Optional<GameSession> sessionForPlayer(UUID playerId) {
        UUID worldId = playerToSessionWorld.get(playerId);
        if (worldId == null) {
            return Optional.empty();
        }
        GameSession session = sessionsByWorld.get(worldId);
        if (session == null) {
            playerToSessionWorld.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public Optional<GameSession> sessionForWorld(World world) {
        return Optional.ofNullable(sessionsByWorld.get(world.getUID()));
    }

    public GameSession startSession(Player player) throws TemplateLoadException, SchematicPlacementException {
        GenerationSettings generation = plugin.settings().generation();
        return startSession(player, generation.templatePatterns(), generation.startPieceId());
    }

    public GameSession startSession(Player player, List<String> templatePatterns, String startPieceId)
            throws TemplateLoadException, SchematicPlacementException {
        if (!Bukkit.isPrimaryThread()) {
            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () -> startSession(player, templatePatterns, startPieceId)).get();
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

        String worldName = "azirouge_" + UUID.randomUUID();
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

            GameSession session = new GameSession(world, result.spawnLocation(), result.placedPieces());
            session.saveLocation(player.getUniqueId(), player.getLocation());
            sessionsByWorld.put(world.getUID(), session);
            playerToSessionWorld.put(player.getUniqueId(), world.getUID());

            if (!player.teleport(result.spawnLocation().clone())) {
                sessionsByWorld.remove(world.getUID());
                playerToSessionWorld.remove(player.getUniqueId());
                restorePlayerAttributes(player);
                throw new IllegalStateException("Failed to teleport player into session world.");
            }

            BukkitTask mobSpawnTask = mobSpawnManager.start(session);
            session.setMobSpawnTask(mobSpawnTask);
            return session;
        } catch (TemplateLoadException | SchematicPlacementException | RuntimeException ex) {
            sessionsByWorld.remove(world.getUID());
            playerToSessionWorld.remove(player.getUniqueId());
            restorePlayerAttributes(player);
            cleanupWorld(world);
            throw ex;
        }
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

        UUID worldId = session.world().getUID();
        File worldFolder = session.world().getWorldFolder();

        cancelMobTask(session);
        evacuatePlayers(session);

        if (!session.world().getPlayers().isEmpty()) {
            plugin.getLogger().warning("Session world still has players after evacuation: " + session.world().getName());
        }

        if (!Bukkit.unloadWorld(session.world(), false)) {
            plugin.getLogger().severe("Failed to unload session world: " + session.world().getName());
            return false;
        }

        deleteWorldFolder(worldFolder.toPath(), session.world().getName());
        sessionsByWorld.remove(worldId);
        clearPlayerMappings(worldId);
        return true;
    }

    public void capturePlayerJoin(Player player, Location previousLocation, World sessionWorld) {
        GameSession targetSession = sessionsByWorld.get(sessionWorld.getUID());
        if (targetSession == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID currentSessionWorld = playerToSessionWorld.get(playerId);
        if (sessionWorld.getUID().equals(currentSessionWorld)) {
            return;
        }

        Location returnLocation = previousLocation.clone();
        if (currentSessionWorld != null) {
            GameSession currentSession = sessionsByWorld.get(currentSessionWorld);
            if (currentSession != null) {
                Location preserved = currentSession.savedLocation(playerId);
                if (preserved != null) {
                    returnLocation = preserved;
                }
                currentSession.removeSavedLocation(playerId);
            }
        }

        targetSession.saveLocation(playerId, returnLocation);
        playerToSessionWorld.put(playerId, sessionWorld.getUID());
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
            capturePlayerJoin(player, from, targetSession.world());
            applyPlayerAttributes(player);
            return;
        }

        restorePlayerAttributes(player);
    }

    public int resolveDepth(World world, Location location) {
        GameSession session = sessionsByWorld.get(world.getUID());
        return session == null ? 0 : session.resolveDepth(location);
    }

    public void shutdown() {
        for (GameSession session : sessionsByWorld.values()) {
            cancelMobTask(session);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            restorePlayerAttributes(player);
        }
    }

    private void evacuatePlayers(GameSession session) {
        List<Player> playersInWorld = new ArrayList<>(session.world().getPlayers());
        for (Player player : playersInWorld) {
            Location returnLocation = resolveReturnLocation(session, player.getUniqueId());
            boolean teleported = returnLocation != null && player.teleport(returnLocation);
            if (!teleported) {
                restorePlayerAttributes(player);
                player.kickPlayer(PREFIX + ChatColor.RED + "Session world is shutting down.");
            }
        }
    }

    private Location resolveReturnLocation(GameSession session, UUID playerId) {
        Location savedLocation = session.savedLocation(playerId);
        if (savedLocation != null
                && savedLocation.getWorld() != null
                && !savedLocation.getWorld().getUID().equals(session.world().getUID())) {
            return savedLocation;
        }

        for (World world : Bukkit.getWorlds()) {
            if (world.getUID().equals(session.world().getUID())) {
                continue;
            }
            return world.getSpawnLocation().clone();
        }
        return null;
    }

    private void clearPlayerMappings(UUID worldId) {
        playerToSessionWorld.entrySet().removeIf(entry -> worldId.equals(entry.getValue()));
    }

    private void cancelMobTask(GameSession session) {
        if (session.mobSpawnTask() != null) {
            session.mobSpawnTask().cancel();
            session.setMobSpawnTask(null);
        }
    }

    private void applyPlayerAttributes(Player player) {
        playerAttributeSnapshots.computeIfAbsent(player.getUniqueId(), ignored -> PlayerAttributeSnapshot.capture(player));
        for (Map.Entry<Attribute, Double> entry : SESSION_ATTRIBUTE_VALUES.entrySet()) {
            AttributeInstance attributeInstance = player.getAttribute(entry.getKey());
            if (attributeInstance != null) {
                attributeInstance.setBaseValue(entry.getValue());
            }
        }

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }
    }

    private void restorePlayerAttributes(Player player) {
        PlayerAttributeSnapshot snapshot = playerAttributeSnapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }

        snapshot.restore(player);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }
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
