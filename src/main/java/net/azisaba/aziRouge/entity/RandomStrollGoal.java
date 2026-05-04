package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RandomStrollGoal implements Goal<Mob> {
    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("azirouge", "random_stroll"));
    private static final double TARGET_REACHED_DISTANCE_SQUARED = 4.0D;
    private static final int MAX_STUCK_TICKS = 80;

    private final Mob mob;
    private final List<Location> targets;
    private final double speed;
    private Location currentTarget;
    private int stuckTicks;

    public RandomStrollGoal(Mob mob, List<Location> targets, double speed) {
        this.mob = mob;
        this.targets = List.copyOf(targets);
        this.speed = speed;
    }

    @Override
    public boolean shouldActivate() {
        return !targets.isEmpty() && mob.isValid() && !mob.isDead();
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
        selectAndMoveToNextTarget();
    }

    @Override
    public void stop() {
        currentTarget = null;
        stuckTicks = 0;
        mob.getPathfinder().stopPathfinding();
    }

    @Override
    public void tick() {
        if (targets.isEmpty()) {
            return;
        }

        if (currentTarget == null || isAtTarget(currentTarget)) {
            selectAndMoveToNextTarget();
            return;
        }

        Pathfinder pathfinder = mob.getPathfinder();
        if (!pathfinder.hasPath()) {
            stuckTicks++;
            if (stuckTicks >= MAX_STUCK_TICKS || !moveTo(currentTarget)) {
                selectAndMoveToNextTarget();
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

    private void selectAndMoveToNextTarget() {
        currentTarget = randomTarget();
        stuckTicks = 0;
        moveTo(currentTarget);
    }

    private Location randomTarget() {
        Location target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        return target.clone();
    }

    private boolean moveTo(Location target) {
        if (target == null || target.getWorld() == null || !target.getWorld().equals(mob.getWorld())) {
            return false;
        }
        return mob.getPathfinder().moveTo(target, speed);
    }

    private boolean isAtTarget(Location target) {
        return target.getWorld() != null
                && target.getWorld().equals(mob.getWorld())
                && mob.getLocation().distanceSquared(target) <= TARGET_REACHED_DISTANCE_SQUARED;
    }
}
