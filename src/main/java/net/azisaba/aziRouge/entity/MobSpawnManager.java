package net.azisaba.aziRouge.entity;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.MobProfileSettings;
import net.azisaba.aziRouge.config.MobSpawnSettings;
import net.azisaba.aziRouge.game.GameSession;
import net.azisaba.aziRouge.dungeon.PlacedPiece;
import net.azisaba.aziRouge.math.BlockBox;
import net.azisaba.aziRouge.math.IntVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class MobSpawnManager {
    private final AziRouge plugin;
    private final MobAiManager mobAiManager;

    public MobSpawnManager(AziRouge plugin, MobAiManager mobAiManager) {
        this.plugin = plugin;
        this.mobAiManager = mobAiManager;
    }

    public BukkitTask start(GameSession session) {
        long intervalTicks = plugin.settings().azirouge().mobSpawn().intervalTicks();
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (session.world().getPlayers().isEmpty()) {
                    return;
                }
                spawnWave(session);
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    private void spawnWave(GameSession session) {
        Random random = ThreadLocalRandom.current();
        MobSpawnSettings mobSpawnSettings = plugin.settings().azirouge().mobSpawn();
        int maxAlivePower = mobSpawnSettings.maxAlivePower();
        if (maxAlivePower <= 0) {
            return;
        }

        int alivePower = currentAlivePower(session.world(), mobSpawnSettings);
        if (alivePower >= maxAlivePower) {
            return;
        }

        for (int index = 0; index < mobSpawnSettings.countPerInterval(); index++) {
            int remainingPower = maxAlivePower - alivePower;
            MobProfile profile = mobSpawnSettings.selectRandomProfile(random, remainingPower);
            if (profile == null) {
                break;
            }
            MobProfileSettings profileSettings = mobSpawnSettings.profile(profile);

            Location spawnLocation = findSpawnLocation(session, random);
            if (spawnLocation == null) {
                continue;
            }

            Entity entity = session.world().spawnEntity(spawnLocation, profile.entityType());
            if (entity instanceof LivingEntity livingEntity) {
                profile.apply(livingEntity, profileSettings);
                if (livingEntity instanceof Mob mob) {
                    Bukkit.getMobGoals().addGoal(mob, 6, new RandomStrollGoal(mob, strollTargets(session), 1.0D));
                }
                mobAiManager.track(livingEntity, profile);
                alivePower += profileSettings.power();
            } else {
                entity.remove();
            }
        }
    }

    private int currentAlivePower(World world, MobSpawnSettings settings) {
        int totalPower = 0;
        for (LivingEntity entity : world.getLivingEntities()) {
            if (!entity.getScoreboardTags().contains(MobProfile.MOB_TAG)) {
                continue;
            }

            MobProfile profile = MobProfile.fromEntity(entity);
            if (profile != null) {
                totalPower += settings.profile(profile).power();
            }
        }
        return totalPower;
    }

    private Location findSpawnLocation(GameSession session, Random random) {
        if (session.placedPieces().isEmpty()) {
            return null;
        }

        for (int attempt = 0; attempt < Math.max(8, session.placedPieces().size() * 2); attempt++) {
            PlacedPiece piece = session.randomRoom(random);
            Location location = findSpawnLocation(session.world(), piece.worldBounds(), random);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    private List<Location> strollTargets(GameSession session) {
        List<Location> targets = new ArrayList<>();
        for (IntVector3 point : session.dungeonStrollPoints()) {
            targets.add(new Location(session.world(), point.x() + 0.5D, point.y(), point.z() + 0.5D));
        }
        return targets;
    }

    private Location findSpawnLocation(World world, BlockBox bounds, Random random) {
        int minX = interiorMin(bounds.minX(), bounds.maxX());
        int maxX = interiorMax(bounds.minX(), bounds.maxX());
        int minZ = interiorMin(bounds.minZ(), bounds.maxZ());
        int maxZ = interiorMax(bounds.minZ(), bounds.maxZ());
        int minY = Math.max(bounds.minY() + 1, world.getMinHeight() + 1);
        int maxY = Math.min(bounds.maxY() - 1, world.getMaxHeight() - 2);

        for (int attempt = 0; attempt < 16; attempt++) {
            int x = randomBetween(minX, maxX, random);
            int z = randomBetween(minZ, maxZ, random);
            for (int y = minY; y <= maxY; y++) {
                Block feet = world.getBlockAt(x, y, z);
                if (!isValidSpawnBlock(feet)) {
                    continue;
                }
                return new Location(world, x + 0.5D, y, z + 0.5D);
            }
        }
        return null;
    }

    private boolean isValidSpawnBlock(Block block) {
        Block floor = block.getRelative(BlockFace.DOWN);
        Block head = block.getRelative(BlockFace.UP);
        return floor.getType().isSolid() && block.isPassable() && head.isPassable() && floor.getLightFromSky() == 0;
    }

    private int randomBetween(int min, int max, Random random) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private int interiorMin(int min, int max) {
        return max - min >= 2 ? min + 1 : min;
    }

    private int interiorMax(int min, int max) {
        return max - min >= 2 ? max - 1 : max;
    }
}
