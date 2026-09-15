package net.azisaba.aziRouge.config;

public record MobAiSettings(
        boolean enabled,
        TorchBreakSettings torchBreak
) {
    public MobAiSettings(boolean enabled) {
        this(enabled, TorchBreakSettings.disabled());
    }
}
