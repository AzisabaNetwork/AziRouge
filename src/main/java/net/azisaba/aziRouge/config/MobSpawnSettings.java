package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.entity.MobProfile;

import java.util.Map;
import java.util.Random;

public record MobSpawnSettings(
        long intervalSeconds,
        int countPerInterval,
        int maxAlivePower,
        MobSpawnLightSettings light,
        Map<String, MobProfileSettings> profiles
) {
    public long intervalTicks() {
        return intervalSeconds * 20L;
    }

    public MobProfileSettings profile(MobProfile profile) {
        MobProfileSettings settings = profiles.get(profile.key());
        return settings == null ? profile.defaultSettings() : settings;
    }

    public MobProfile selectRandomProfile(Random random, int remainingPower, Map<MobProfile, Integer> aliveCounts) {
        if (remainingPower <= 0) {
            return null;
        }

        int totalWeight = 0;
        for (MobProfile profile : MobProfile.values()) {
            MobProfileSettings settings = profile(profile);
            if (!canSpawnProfile(profile, settings, remainingPower, aliveCounts)) {
                continue;
            }
            totalWeight += settings.weight();
        }
        if (totalWeight <= 0) {
            return null;
        }

        int cursor = random.nextInt(totalWeight);
        for (MobProfile profile : MobProfile.values()) {
            MobProfileSettings settings = profile(profile);
            if (!canSpawnProfile(profile, settings, remainingPower, aliveCounts)) {
                continue;
            }
            cursor -= settings.weight();
            if (cursor < 0) {
                return profile;
            }
        }
        return null;
    }

    private boolean canSpawnProfile(
            MobProfile profile,
            MobProfileSettings settings,
            int remainingPower,
            Map<MobProfile, Integer> aliveCounts
    ) {
        if (settings.weight() <= 0 || settings.power() > remainingPower) {
            return false;
        }
        int maxAliveCount = settings.maxAliveCount();
        return maxAliveCount < 0 || aliveCounts.getOrDefault(profile, 0) < maxAliveCount;
    }
}
