package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Bukkit;
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
    private int openTick;

    public DoorOpenGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean shouldActivate() {
        if (!mob.getPathfinder().hasPath() || !mob.isOnGround()) {
            return false;
        }
        Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        for (int i = 0; i < path.getPoints().size(); i++) {
            Location point = path.getPoints().get(i);
            if (point.distanceSquared(mob.getLocation()) < 5
                    && point.clone().add(0, 1, 0).getBlock().getType() == Material.SPRUCE_DOOR) {
                targetDoor = point;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldStayActive() {
        return Bukkit.getCurrentTick() - openTick < 20;
    }

    @Override
    public void start() {
        if (targetDoor == null) return;

        Block b = mob.getWorld().getBlockAt(targetDoor);
        BlockData data = b.getBlockData();
        if (data instanceof Openable door) {
            door.setOpen(true);
            b.setBlockData(door);
            this.openTick = Bukkit.getCurrentTick();
        }
    }

    @Override
    public void stop() {
        if (targetDoor == null) return;

        Block b = mob.getWorld().getBlockAt(targetDoor);
        BlockData data = b.getBlockData();
        if (data instanceof Openable door) {
            door.setOpen(false);
            b.setBlockData(door);
        }
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
