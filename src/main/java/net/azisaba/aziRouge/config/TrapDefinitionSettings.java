package net.azisaba.aziRouge.config;

public record TrapDefinitionSettings(
        String key,
        int weight,
        String displayMaterial,
        double triggerRadius,
        float explosionPower,
        boolean setFire,
        boolean breakBlocks,
        int minDepth,
        int maxDepth
) {
    public boolean matchesDepth(int depth) {
        return depth >= minDepth && depth <= maxDepth;
    }
}
