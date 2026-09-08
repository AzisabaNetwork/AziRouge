package net.azisaba.aziRouge.config;

import org.bukkit.Material;

public record MiningOreSettings(
        Material material,
        int weight,
        int minDepth,
        int maxDepth
) {
    public boolean matchesDepth(int depth) {
        return depth >= minDepth && depth <= maxDepth;
    }
}
