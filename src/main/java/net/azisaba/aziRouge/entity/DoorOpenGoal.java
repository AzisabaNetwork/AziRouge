package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

public class DoorOpenGoal implements Goal<Mob> {
    private final Mob mob;
    private Location targetDoor;

    public DoorOpenGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean shouldActivate() {
        if (!mob.getPathfinder().hasPath() || !mob.isOnGround()) {
            return false;
        }
        Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        for (int i = 0; i < Math.min(path.getNextPointIndex() + 2, path.getPoints().size()); i++) {
            Location point = path.getPoints().get(i);
            if (point.distanceSquared(mob.getLocation()) < 5
                    && point.clone().add(0, 1, 0).getBlock().getType() == Material.CHERRY_DOOR) {
                targetDoor = point;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldStayActive() {
        return false;
    }

    @Override
    public void start() {
        if (targetDoor == null) return;

        Block b = mob.getWorld().getBlockAt(targetDoor);
        BlockData data = b.getBlockData();
        if (data instanceof Openable door) {
            door.setOpen(true);
            b.setBlockData(door);
        }
    }

    @Override
    public void stop() {
        Goal.super.stop();
    }

    @Override
    public void tick() {
        Goal.super.tick();
    }

    @Override
    public GoalKey<Mob> getKey() {
        return VanillaGoal.OPEN_DOOR;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.noneOf(GoalType.class);
    }
}
