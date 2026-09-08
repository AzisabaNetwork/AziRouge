package net.azisaba.aziRouge.game;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.GuiSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GameMenuService implements Listener {
    private static final String TAG_CREATE_SESSION = "create_session";
    private static final String TAG_JOIN_SESSION = "join_session";
    private static final String TAG_LEAVE_SESSION = "leave_session";
    private static final String TAG_START_ROUND = "start_round";
    private static final String TAG_END_ROUND = "end_round";
    private static final String TAG_ROUND = "round";
    private static final String TAG_MENU = "menu";

    private static final String SESSION = "Session";
    private static final String ROUND = "Round";
    private static final String CREATE = "Create";
    private static final String JOIN = "Join";
    private static final String LIST = "List";
    private static final String LEAVE = "Leave";
    private static final String START = "Start";
    private static final String END = "End";
    private static final String MENU = "Menu";
    private static final String CLOSE = "Close";
    private static final String DEPTH = "Depth";
    private static final String SESSION_ID = "Session ID";

    private final AziRouge plugin;

    public GameMenuService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void refresh() {
        // Dialog trigger entities are placed in-game and detected by scoreboard tag.
    }

    public void shutdown() {
        // This service does not own any entities.
    }

    public void openMenu(Player player) {
        showMenuDialog(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Optional<String> trigger = resolveTrigger(event.getRightClicked());
        if (trigger.isEmpty()) {
            return;
        }

        if (TAG_CREATE_SESSION.equals(trigger.get())) {
            event.setCancelled(true);
            showCreateSessionDialog(event.getPlayer());
        } else if (TAG_JOIN_SESSION.equals(trigger.get())) {
            event.setCancelled(true);
            showJoinSessionDialog(event.getPlayer());
        } else if (TAG_LEAVE_SESSION.equals(trigger.get())) {
            event.setCancelled(true);
            showLeaveSessionConfirmation(event.getPlayer());
        } else if (TAG_START_ROUND.equals(trigger.get())) {
            event.setCancelled(true);
            showStartRoundDialog(event.getPlayer());
        } else if (TAG_END_ROUND.equals(trigger.get())) {
            event.setCancelled(true);
            showEndRoundConfirmation(event.getPlayer());
        } else if (TAG_ROUND.equals(trigger.get())) {
            event.setCancelled(true);
            showRoundDialog(event.getPlayer());
        } else if (TAG_MENU.equals(trigger.get())) {
            event.setCancelled(true);
            showMenuDialog(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isRightClick(event.getAction())) {
            return;
        }
        ItemStack item = event.getItem();
        if (SpectatorItemSupport.isSwitchTargetItem(plugin, item)) {
            event.setCancelled(true);
            plugin.gameSessionManager().handleSpectatorCompass(event.getPlayer());
        } else if (GameOverItemSupport.isLeaveItem(plugin, item)) {
            event.setCancelled(true);
            showLeaveSessionConfirmation(event.getPlayer());
        } else if (GameOverItemSupport.isMenuItem(plugin, item)) {
            event.setCancelled(true);
            showMenuDialog(event.getPlayer());
        }
    }

    private void showCreateSessionDialog(Player player) {
        showConfirmationDialog(
                player,
                message("menu.create-session", "Create Session"),
                message("menu.create-session-description", "Create a new session."),
                action(label("menu.create-session", "Create"), label("menu.tooltip.create-session", "Create a session and move to the home world."), "/azirouge session create --confirm"),
                closeAction()
        );
    }

    private void showJoinSessionDialog(Player player) {
        showDialog(
                player,
                message("menu.join-session", "Join Session"),
                message("menu.join-session-description", "Enter the session ID to join."),
                List.of(sessionIdInput()),
                List.of(action(label("menu.join-session", "Join"), label("menu.tooltip.join-session", "Join the entered session."), "/azirouge session join $(sessionId) --confirm")),
                1
        );
    }

    private void showLeaveSessionConfirmation(Player player) {
        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        String description = session == null
                ? label("menu.leave-no-session", "You are not currently in a session.")
                : plugin.messages().format("menu.leave-session-description", "Leave session {session}? You can join another session after leaving.", "session", session.sessionId());
        showConfirmationDialog(
                player,
                message("menu.leave-session", "Leave Session"),
                text(description),
                action(label("menu.leave-session", "Leave Session"), label("menu.tooltip.leave-session", "Leave your current session."), "/azirouge session leave --confirm"),
                menuAction(label("menu.back", "Back"), label("menu.tooltip.back-session", "Return to the session menu."), this::showSessionMenuDialog)
        );
    }

    private void showStartRoundDialog(Player player) {
        showDialog(
                player,
                message("menu.start-round", "Start Round"),
                message("menu.start-round-description", "Choose a depth and start the round."),
                List.of(depthInput()),
                List.of(action(label("menu.start-round", "Start"), label("menu.tooltip.start-round", "Start the round with the selected depth."), "/azirouge round start test $(depth) --confirm")),
                1
        );
    }

    private void showEndRoundConfirmation(Player player) {
        showConfirmationDialog(
                player,
                message("menu.end-round", "End Round"),
                message("menu.end-round-description", "End the current round? Loot will be sold and maintenance will be charged."),
                action(label("menu.end-round", "End Round"), label("menu.tooltip.end-round", "End the current round."), "/azirouge round end --confirm"),
                closeAction()
        );
    }

    private void showMenuDialog(Player player) {
        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        showDialog(
                player,
                message("menu.title", "AziRouge Menu"),
                text(menuDescription(session)),
                List.of(),
                List.of(
                        menuAction(label("menu.session-title", "Session"), label("menu.tooltip.session", "Open session actions."), this::showSessionMenuDialog),
                        menuAction(label("menu.round-title", "Round"), label("menu.tooltip.round", "Open round actions."), this::showRoundDialog),
                        action(label("menu.list-sessions", "List Sessions"), label("menu.tooltip.list-sessions", "Show active sessions in chat."), "/azirouge session list"),
                        closeAction()
                ),
                2
        );
    }

    private void showSessionMenuDialog(Player player) {
        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        List<ActionButton> actions = new ArrayList<>();
        actions.add(menuAction(label("menu.create-session", "Create Session"), label("menu.tooltip.create-session-open", "Open the create session confirmation."), this::showCreateSessionDialog));
        actions.add(menuAction(label("menu.join-session", "Join Session"), label("menu.tooltip.join-session-open", "Enter a session ID and join."), this::showJoinSessionDialog));
        actions.add(action(label("menu.list-sessions", "List Sessions"), label("menu.tooltip.list-sessions-joinable", "Show joinable sessions in chat."), "/azirouge session list"));
        actions.add(menuAction(label("menu.leave-session", "Leave Session"), label("menu.tooltip.leave-session-open", "Open the leave session confirmation."), this::showLeaveSessionConfirmation));
        actions.add(menuAction(label("menu.back", "Back"), label("menu.tooltip.back-main", "Return to the main menu."), this::showMenuDialog));

        showDialog(
                player,
                message("menu.session-title", "Session Menu"),
                text(menuDescription(session)),
                List.of(),
                actions,
                2
        );
    }

    private void showRoundDialog(Player player) {
        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        List<ActionButton> actions = new ArrayList<>();
        if (session == null) {
            actions.add(menuAction(label("menu.join-session", "Join Session"), label("menu.tooltip.join-session-open", "Enter a session ID and join."), this::showJoinSessionDialog));
            actions.add(menuAction(label("menu.create-session", "Create Session"), label("menu.tooltip.create-session-open", "Open the create session confirmation."), this::showCreateSessionDialog));
        } else if (session.state() == SessionState.LOBBY || session.state() == SessionState.BETWEEN_ROUNDS) {
            actions.add(menuAction(label("menu.start-round", "Start Round"), label("menu.tooltip.start-round-open", "Choose a depth and start the round."), this::showStartRoundDialog));
            actions.add(menuAction(label("menu.leave-session", "Leave Session"), label("menu.tooltip.leave-session-open", "Open the leave session confirmation."), this::showLeaveSessionConfirmation));
        } else if (session.state() == SessionState.IN_ROUND) {
            actions.add(menuAction(label("menu.end-round", "End Round"), label("menu.tooltip.end-round-open", "Open the end round confirmation."), this::showEndRoundConfirmation));
        } else if (session.state() == SessionState.GAME_OVER) {
            actions.add(menuAction(label("menu.leave-session", "Leave Session"), label("menu.tooltip.leave-session-open", "Open the leave session confirmation."), this::showLeaveSessionConfirmation));
            actions.add(menuAction(label("menu.create-session", "Create Session"), label("menu.tooltip.create-session-open", "Open the create session confirmation."), this::showCreateSessionDialog));
        }
        actions.add(menuAction(label("menu.back", "Back"), label("menu.tooltip.back-main", "Return to the main menu."), this::showMenuDialog));

        showDialog(
                player,
                message("menu.round-title", "Round Menu"),
                text(roundDescription(session)),
                List.of(),
                actions,
                2
        );
    }

    private void showDialog(
            Player player,
            Component title,
            Component description,
            List<DialogInput> inputs,
            List<ActionButton> actions,
            int columns
    ) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .body(List.of(DialogBody.plainMessage(description, 260)))
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .build())
                .type(DialogType.multiAction(actions)
                        .columns(columns)
                        .exitAction(closeAction())
                        .build()));
        player.showDialog(dialog);
    }

    private void showConfirmationDialog(
            Player player,
            Component title,
            Component description,
            ActionButton yes,
            ActionButton no
    ) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .body(List.of(DialogBody.plainMessage(description, 260)))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .build())
                .type(DialogType.confirmation(yes, no)));
        player.showDialog(dialog);
    }

    private ActionButton action(String label, String tooltip, String commandTemplate) {
        return ActionButton.create(
                text(label),
                text(tooltip),
                160,
                commandTemplate.contains("$(")
                        ? DialogAction.commandTemplate(commandTemplate)
                        : DialogAction.staticAction(ClickEvent.runCommand(commandTemplate))
        );
    }

    private ActionButton menuAction(String label, String tooltip, PlayerDialogCallback callback) {
        return ActionButton.create(
                text(label),
                text(tooltip),
                160,
                DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player player) {
                                callback.accept(player);
                            }
                        },
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(ClickCallback.DEFAULT_LIFETIME)
                                .build()
                )
        );
    }

    private ActionButton closeAction() {
        return ActionButton.create(
                message("menu.close", CLOSE),
                message("menu.tooltip.close", "Do nothing."),
                80,
                null
        );
    }

    private DialogInput sessionIdInput() {
        return DialogInput.text(
                "sessionId",
                200,
                text(SESSION_ID),
                true,
                "",
                6,
                null
        );
    }

    private DialogInput depthInput() {
        GuiSettings settings = plugin.settings().gui();
        return DialogInput.numberRange(
                "depth",
                200,
                text(DEPTH),
                "%s: %s",
                1.0F,
                settings.maxDepth(),
                (float) settings.defaultDepth(),
                1.0F
        );
    }

    private String menuDescription(GameSession session) {
        if (session == null) {
            return label("menu.description.no-session", "Create a session, join a session, or list active sessions.");
        }
        return plugin.messages().format("menu.description.session",
                "Current session: {session}\nState: {state}\nShared balance: {balance}\nRound: {round}",
                "session", session.sessionId(),
                "state", session.state(),
                "balance", session.sharedBalance(),
                "round", session.currentRound());
    }

    private String roundDescription(GameSession session) {
        if (session == null) {
            return label("menu.description.round-no-session", "You are not in a session. Join or create a session before starting a round.");
        }
        return plugin.messages().format("menu.description.round",
                "Session: {session}\nState: {state}\nRound: {round}\nDepth: {depth}\nShared balance: {balance}",
                "session", session.sessionId(),
                "state", session.state(),
                "round", session.currentRound(),
                "depth", session.getMaxDepth(),
                "balance", session.sharedBalance());
    }

    private Component text(String value) {
        return Component.text(value);
    }

    private Component message(String key, String fallback) {
        return Component.text(label(key, fallback));
    }

    private String label(String key, String fallback) {
        return plugin.messages().text(key, fallback);
    }

    private Optional<String> resolveTrigger(Entity clicked) {
        Optional<String> direct = triggerFromTags(clicked.getScoreboardTags());
        if (direct.isPresent()) {
            return direct;
        }
        return clicked.getNearbyEntities(1.25D, 1.25D, 1.25D).stream()
                .map(entity -> triggerFromTags(entity.getScoreboardTags()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private Optional<String> triggerFromTags(Set<String> tags) {
        for (String tag : List.of(
                TAG_CREATE_SESSION,
                TAG_JOIN_SESSION,
                TAG_LEAVE_SESSION,
                TAG_START_ROUND,
                TAG_END_ROUND,
                TAG_ROUND,
                TAG_MENU
        )) {
            if (tags.contains(tag)) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    private boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    @FunctionalInterface
    private interface PlayerDialogCallback {
        void accept(Player player);
    }
}
