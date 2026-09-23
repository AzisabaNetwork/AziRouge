package net.azisaba.aziRouge.entity;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.azisaba.aziRouge.config.CreakingAiSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.EnumSet;

public final class CreakingChaseGoal implements Goal<Creaking> {
    private static final GoalKey<Creaking> KEY = GoalKey.of(Creaking.class, new NamespacedKey("azirouge", "creaking_chase"));
    private final Creaking creaking;
    private final CreakingAiSettings settings;
    private Player target;
    private int lastSeenTick;

    public CreakingChaseGoal(Creaking creaking, CreakingAiSettings settings) {
        this.creaking = creaking;
        this.settings = settings;
    }

    @Override
    public boolean shouldActivate() {
        target = nearestVisiblePlayer();
        return target != null;
    }

    @Override
    public boolean shouldStayActive() {
        return target != null && creaking.isValid() && !creaking.isDead();
    }

    @Override
    public void start() {
        lastSeenTick = Bukkit.getCurrentTick();
        activateTarget();
    }

    @Override
    public void tick() {
        int now = Bukkit.getCurrentTick();
        Player visible = nearestVisiblePlayer();
        if (visible != null) {
            lastSeenTick = now;
            if (target == null || !target.getUniqueId().equals(visible.getUniqueId())) {
                target = visible;
            }
        } else if (target == null || !target.isOnline() || target.isDead()
                || target.getWorld() != creaking.getWorld()
                || now - lastSeenTick >= settings.forgetAfterTicks()) {
            target = null;
            creaking.deactivate();
            return;
        }

        activateTarget();
    }

    @Override
    public void stop() {
        target = null;
        if (creaking.isActive()) {
            creaking.deactivate();
        }
    }

    @Override
    public GoalKey<Creaking> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.TARGET);
    }

    private Player nearestVisiblePlayer() {
        Player nearest = null;
        Location location = creaking.getLocation();
        double nearestDistance = settings.sightRange() * settings.sightRange();
        for (Player player : creaking.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE
                    || !player.canSee(creaking) || !player.hasLineOfSight(creaking)) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void activateTarget() {
        LivingEntity current = creaking.getTarget();
        if (target != null && (!creaking.isActive() || current == null
                || !current.getUniqueId().equals(target.getUniqueId()))) {
            creaking.activate(target);
        }
    }
}
