package net.azisaba.aziRouge.game;

public enum SessionState {
    LOBBY,
    BETWEEN_ROUNDS,
    IN_ROUND,
    GAME_OVER,
    CLOSING;

    public String getDisplayName() {
        return switch (this) {
            case LOBBY -> "Lobby";
            case BETWEEN_ROUNDS -> "Between Rounds";
            case IN_ROUND -> "In Round";
            case GAME_OVER -> "Game Over";
            case CLOSING -> "Closing";
        };
    }
}
