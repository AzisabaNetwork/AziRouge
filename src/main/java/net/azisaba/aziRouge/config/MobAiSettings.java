package net.azisaba.aziRouge.config;

public record MobAiSettings(
        boolean enabled,
        long tickIntervalTicks,
        TorchBreakSettings torchBreak
) {
    public MobAiSettings(boolean enabled, long tickIntervalTicks) {
        this(enabled, tickIntervalTicks, TorchBreakSettings.disabled());
    }
}
