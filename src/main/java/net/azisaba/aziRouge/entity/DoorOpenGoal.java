package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

public class DoorOpenGoal implements Goal<Mob> {
    private final Mob mob;
    private final int delayTicks;
    private Block targetDoor;
    private int startTick;
    private boolean opened;

    public DoorOpenGoal(Mob mob) {
        this(mob, 0);
    }

    public DoorOpenGoal(Mob mob, int delayTicks) {
        this.mob = mob;
        this.delayTicks = delayTicks;
    }

    @Override
    public boolean shouldActivate() {
        if (!mob.getPathfinder().hasPath() || !mob.isOnGround()) {
            return false;
        }
        Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        if (path == null) {
            return false;
        }
        for (int i = 0; i < path.getPoints().size(); i++) {
            Location point = path.getPoints().get(i);
            if (point.distanceSquared(mob.getLocation()) >= 5) {
                continue;
            }
            Block block = point.getBlock();
            if (!(block.getBlockData() instanceof Door)) {
                block = block.getRelative(BlockFace.UP);
            }
            if (block.getBlockData() instanceof Door door) {
                targetDoor = door.getHalf() == Door.Half.TOP
                        ? block.getRelative(BlockFace.DOWN) : block;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldStayActive() {
        return targetDoor != null && Bukkit.getCurrentTick() - startTick < delayTicks + 20;
    }

    @Override
    public void start() {
        startTick = Bukkit.getCurrentTick();
        opened = false;
        if (delayTicks == 0) {
            setOpen(true);
        }
    }

    @Override
    public void stop() {
        if (opened) {
            setOpen(false);
        }
        targetDoor = null;
    }

    @Override
    public void tick() {
        if (!opened && Bukkit.getCurrentTick() - startTick >= delayTicks) {
            setOpen(true);
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return VanillaGoal.OPEN_DOOR;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.noneOf(GoalType.class);
    }

    private void setOpen(boolean open) {
        if (targetDoor == null) {
            return;
        }
        BlockData lowerData = targetDoor.getBlockData();
        Block upper = targetDoor.getRelative(BlockFace.UP);
        BlockData upperData = upper.getBlockData();
        if (!(lowerData instanceof Door lower) || !(upperData instanceof Door top)) {
            return;
        }
        lower.setOpen(open);
        top.setOpen(open);
        targetDoor.setBlockData(lower);
        upper.setBlockData(top);
        opened = open;
    }
}
