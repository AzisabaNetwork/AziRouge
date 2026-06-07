package net.azisaba.aziRouge.config;

public record TorchBreakSettings(
        boolean enabled,
        int searchRadius,
        double breakDistance,
        int breakTicks,
        double goalSpeed
) {
    public static TorchBreakSettings disabled() {
        return new TorchBreakSettings(false, 10, 1.8D, 80, 0.7D);
    }
}
