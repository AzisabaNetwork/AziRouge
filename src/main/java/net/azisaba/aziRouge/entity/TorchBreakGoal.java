package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.azisaba.aziRouge.config.TorchBreakSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class TorchBreakGoal implements Goal<Mob> {
    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("azirouge", "torch_break"));
    private static final int MAX_STUCK_TICKS = 80;
    private static final int UNREACHABLE_RETRY_TICKS = 200;

    private final Mob mob;
    private final Set<Material> torchMaterials;
    private final TorchBreakSettings settings;
    private final Map<BlockPosition, Integer> unreachableUntilTick = new HashMap<>();
    private Block target;
    private int breakProgress;
    private int stuckTicks;
    private boolean active;

    public TorchBreakGoal(Mob mob, Set<Material> torchMaterials, TorchBreakSettings settings) {
        this.mob = mob;
        this.torchMaterials = Set.copyOf(torchMaterials);
        this.settings = settings;
    }

    @Override
    public boolean shouldActivate() {
        if (active
                || !settings.enabled()
                || torchMaterials.isEmpty()
                || !mob.isValid()
                || mob.isDead()
                || mob.getTarget() != null) {
            return false;
        }
        target = findNearestTorch();
        return target != null;
    }

    @Override
    public boolean shouldStayActive() {
        return active
                && settings.enabled()
                && mob.isValid()
                && !mob.isDead()
                && isValidTarget(target);
    }

    @Override
    public void start() {
        breakProgress = 0;
        stuckTicks = 0;
        if (!isValidTarget(target)) {
            target = findNearestTorch();
        }
        active = isValidTarget(target);
        if (!active) {
            finishGoal();
            return;
        }
        moveToAvailableTarget();
    }

    @Override
    public void stop() {
        finishGoal();
    }

    private void finishGoal() {
        active = false;
        target = null;
        breakProgress = 0;
        stuckTicks = 0;
        PathfindTargetRegistry.clear(mob);
        mob.getPathfinder().stopPathfinding();
    }

    @Override
    public void tick() {
        if (!isValidTarget(target)) {
            target = findNearestTorch();
            breakProgress = 0;
            stuckTicks = 0;
            if (target == null) {
                finishGoal();
                return;
            }
        }

        if (isCloseEnough(target)) {
            mob.getPathfinder().stopPathfinding();
            breakProgress++;
            if (breakProgress >= settings.breakTicks()) {
                target.setType(Material.AIR, false);
                target = findNearestTorch();
                breakProgress = 0;
                stuckTicks = 0;
                if (target == null) {
                    finishGoal();
                } else {
                    moveToAvailableTarget();
                }
            }
            return;
        }

        breakProgress = 0;
        Pathfinder pathfinder = mob.getPathfinder();
        if (!pathfinder.hasPath()) {
            stuckTicks++;
            if (stuckTicks >= MAX_STUCK_TICKS || !moveToTarget()) {
                markTargetUnreachable();
                target = findNearestTorch();
                breakProgress = 0;
                stuckTicks = 0;
                moveToAvailableTarget();
            }
        } else {
            stuckTicks = 0;
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }

    private Block findNearestTorch() {
        World world = mob.getWorld();
        Location origin = mob.getLocation();
        int radius = settings.searchRadius();
        int minY = Math.max(world.getMinHeight(), origin.getBlockY() - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, origin.getBlockY() + radius);
        int radiusSquared = radius * radius;
        Block nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int x = origin.getBlockX() - radius; x <= origin.getBlockX() + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = origin.getBlockZ() - radius; z <= origin.getBlockZ() + radius; z++) {
                    int dx = x - origin.getBlockX();
                    int dy = y - origin.getBlockY();
                    int dz = z - origin.getBlockZ();
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (!torchMaterials.contains(block.getType()) || isTemporarilyUnreachable(block)) {
                        continue;
                    }

                    double distance = origin.distanceSquared(block.getLocation().add(0.5D, 0.5D, 0.5D));
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = block;
                    }
                }
            }
        }
        return nearest;
    }

    private void moveToAvailableTarget() {
        while (isValidTarget(target)) {
            if (moveToTarget()) {
                return;
            }
            markTargetUnreachable();
            target = findNearestTorch();
        }
        finishGoal();
    }

    private boolean moveToTarget() {
        if (!isValidTarget(target)) {
            return false;
        }
        Location targetLocation = target.getLocation().add(0.5D, 0.0D, 0.5D);
        PathfindTargetRegistry.allow(mob, targetLocation);
        return mob.getPathfinder().moveTo(targetLocation, settings.goalSpeed());
    }

    private void markTargetUnreachable() {
        if (target == null) {
            return;
        }
        unreachableUntilTick.put(
                BlockPosition.of(target),
                Bukkit.getCurrentTick() + UNREACHABLE_RETRY_TICKS
        );
    }

    private boolean isTemporarilyUnreachable(Block block) {
        BlockPosition position = BlockPosition.of(block);
        Integer retryTick = unreachableUntilTick.get(position);
        if (retryTick == null) {
            return false;
        }
        if (Bukkit.getCurrentTick() >= retryTick) {
            unreachableUntilTick.remove(position);
            return false;
        }
        return true;
    }

    private boolean isCloseEnough(Block block) {
        double distanceSquared = settings.breakDistance() * settings.breakDistance();
        return mob.getLocation().distanceSquared(block.getLocation().add(0.5D, 0.5D, 0.5D)) <= distanceSquared;
    }

    private boolean isValidTarget(Block block) {
        return block != null
                && block.getWorld().equals(mob.getWorld())
                && torchMaterials.contains(block.getType());
    }

    private record BlockPosition(int x, int y, int z) {
        private static BlockPosition of(Block block) {
            return new BlockPosition(block.getX(), block.getY(), block.getZ());
        }
    }
}
