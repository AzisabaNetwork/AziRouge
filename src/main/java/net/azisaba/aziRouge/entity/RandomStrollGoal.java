package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;

import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;

public class RandomStrollGoal implements Goal<Mob> {
    private final Mob mob;

    public RandomStrollGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean shouldActivate() {
        return false;
    }

    @Override
    public boolean shouldStayActive() {
        return Goal.super.shouldStayActive();
    }

    @Override
    public void start() {
        Goal.super.start();
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
        return null;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return null;
    }
}
