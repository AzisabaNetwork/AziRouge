package net.azisaba.aziRouge.config;

public record MobAiSettings(
        boolean enabled,
        TorchBreakSettings torchBreak,
        CreakingAiSettings creaking
) {
    public MobAiSettings(boolean enabled) {
        this(enabled, TorchBreakSettings.disabled(), CreakingAiSettings.defaults());
    }
}
