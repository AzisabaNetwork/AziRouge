package net.azisaba.aziRouge.author;

import org.bukkit.entity.Player;

public final class MissingSelectionProvider implements SelectionProvider {
    private final String reason;

    public MissingSelectionProvider(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describeAvailability() {
        return reason;
    }

    @Override
    public SelectionSnapshot getSelection(Player player) throws SelectionLookupException {
        throw new SelectionLookupException(reason);
    }
}
