package net.azisaba.aziRouge.config;

import net.azisaba.aziRouge.entity.MobProfile;

import java.util.Map;
import java.util.Random;

public record MobSpawnSettings(
        long intervalSeconds,
        int countPerInterval,
        int maxAlivePower,
        Map<String, MobProfileSettings> profiles
) {
    public long intervalTicks() {
        return intervalSeconds * 20L;
    }

    public MobProfileSettings profile(MobProfile profile) {
        MobProfileSettings settings = profiles.get(profile.key());
        return settings == null ? profile.defaultSettings() : settings;
    }

    public MobProfile selectRandomProfile(Random random, int remainingPower) {
        if (remainingPower <= 0) {
            return null;
        }

        int totalWeight = 0;
        for (MobProfile profile : MobProfile.values()) {
            MobProfileSettings settings = profile(profile);
            if (settings.weight() <= 0 || settings.power() > remainingPower) {
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
            if (settings.weight() <= 0 || settings.power() > remainingPower) {
                continue;
            }
            cursor -= settings.weight();
            if (cursor < 0) {
                return profile;
            }
        }
        return null;
    }
}
