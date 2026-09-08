package net.azisaba.aziRouge.game;

public enum SessionState {
    LOBBY,
    BETWEEN_ROUNDS,
    IN_ROUND,
    GAME_OVER,
    CLOSING;

    public String displayKey() {
        return switch (this) {
            case LOBBY -> "lobby";
            case BETWEEN_ROUNDS -> "between-rounds";
            case IN_ROUND -> "in-round";
            case GAME_OVER -> "game-over";
            case CLOSING -> "closing";
        };
    }
}
