package net.azisaba.aziRouge.config;

public record RoundTimingSettings(
        long startTimeTicks,
        long deadlineTimeTicks,
        int minimumSleepingPercentage,
        int sleepDelaySeconds
) {
}
