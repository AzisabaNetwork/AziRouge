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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GameMenuService implements Listener {
    private static final String TAG_CREATE_SESSION = "create_session";
    private static final String TAG_JOIN_SESSION = "join_session";
    private static final String TAG_START_ROUND = "start_round";
    private static final String TAG_END_ROUND = "end_round";
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

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity clicked = event.getRightClicked();
        Set<String> tags = clicked.getScoreboardTags();
        if (tags.contains(TAG_CREATE_SESSION)) {
            event.setCancelled(true);
            showCreateSessionDialog(event.getPlayer());
        } else if (tags.contains(TAG_JOIN_SESSION)) {
            event.setCancelled(true);
            showJoinSessionDialog(event.getPlayer());
        } else if (tags.contains(TAG_START_ROUND)) {
            event.setCancelled(true);
            showStartRoundDialog(event.getPlayer());
        } else if (tags.contains(TAG_END_ROUND)) {
            event.setCancelled(true);
            event.getPlayer().performCommand("azirouge round end");
        } else if (tags.contains(TAG_MENU)) {
            event.setCancelled(true);
            showMenuDialog(event.getPlayer());
        }
    }

    private void showCreateSessionDialog(Player player) {
        showDialog(
                player,
                text("Create Session"),
                text("Create a new session."),
                List.of(),
                List.of(action("Create", "Create a session and move to the home world.", "/azirouge session create")),
                1
        );
    }

    private void showJoinSessionDialog(Player player) {
        showDialog(
                player,
                text("Join Session"),
                text("Enter the session ID to join."),
                List.of(sessionIdInput()),
                List.of(action("Join", "Join the entered session.", "/azirouge session join $(sessionId)")),
                1
        );
    }

    private void showStartRoundDialog(Player player) {
        showDialog(
                player,
                text("Start Round"),
                text("Choose a depth and start the round."),
                List.of(depthInput()),
                List.of(action("Start", "Start the round with the selected depth.", "/azirouge round start default $(depth)")),
                1
        );
    }

    private void showMenuDialog(Player player) {
        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        List<ActionButton> actions = new ArrayList<>();
        actions.add(action("Create Session", "Create a new session.", "/azirouge session create"));
        actions.add(action("Join Session", "Join the entered session ID.", "/azirouge session join $(sessionId)"));
        actions.add(action("List Sessions", "Show joinable sessions in chat.", "/azirouge session list"));
        actions.add(action("Leave Session", "Leave your current session.", "/azirouge session leave"));
        actions.add(action("Start Round", "Start the round with the selected depth.", "/azirouge round start default $(depth)"));
        actions.add(action("End Round", "End the current round.", "/azirouge round end"));

        showDialog(
                player,
                text("AziRouge " + MENU),
                text(menuDescription(session)),
                List.of(sessionIdInput(), depthInput()),
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

    private ActionButton action(String label, String tooltip, String commandTemplate) {
        return ActionButton.create(
                text(label),
                text(tooltip),
                160,
                DialogAction.commandTemplate(commandTemplate)
        );
    }

    private ActionButton closeAction() {
        return ActionButton.create(
                text(CLOSE),
                text("Do nothing."),
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
                32,
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
                null
        );
    }

    private String menuDescription(GameSession session) {
        if (session == null) {
            return "Create a session, join a session, or list active sessions. Enter a session ID before joining.";
        }
        return "Current session: " + session.sessionId()
                + "\nState: " + session.state()
                + "\nShared balance: " + session.sharedBalance()
                + "\nChoose a depth before starting a round.";
    }

    private Component text(String value) {
        return Component.text(value);
    }
}
