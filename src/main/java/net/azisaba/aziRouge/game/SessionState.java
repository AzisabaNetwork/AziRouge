package net.azisaba.aziRouge.game;

public enum SessionState {
    LOBBY,
    IN_ROUND,
    GAME_OVER,
    CLOSING;

    public String displayKey() {
        return switch (this) {
            case LOBBY -> "lobby";
            case IN_ROUND -> "in-round";
            case GAME_OVER -> "game-over";
            case CLOSING -> "closing";
        };
    }
}
