package net.azisaba.aziRouge.config;

public record CreakingAiSettings(
        double sightRange,
        int forgetAfterTicks,
        double strollSpeed,
        int doorOpenDelayTicks
) {
    public static CreakingAiSettings defaults() {
        return new CreakingAiSettings(32.0D, 200, 1.0D, 30);
    }
}
