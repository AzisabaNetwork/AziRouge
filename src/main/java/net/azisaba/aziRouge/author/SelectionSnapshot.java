package net.azisaba.aziRouge.author;

import net.azisaba.aziRouge.math.IntVector3;

public record SelectionSnapshot(String worldName, IntVector3 min, IntVector3 max) {
}
