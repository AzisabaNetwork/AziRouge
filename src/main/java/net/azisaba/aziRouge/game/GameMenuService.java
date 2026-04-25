package net.azisaba.aziRouge.game;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.GuiSettings;
import net.azisaba.aziRouge.math.IntVector3;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GameMenuService implements Listener {
    private static final String PREFIX = ChatColor.GOLD + "[Azirouge] " + ChatColor.RESET;

    private final AziRouge plugin;
    private UUID textDisplayId;

    public GameMenuService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void refresh() {
        removeTextDisplay();
        GuiSettings settings = plugin.settings().gui();
        World world = Bukkit.getWorld(settings.worldName());
        if (world == null) {
            plugin.getLogger().warning("GUI用TextDisplayのworldが見つかりません: " + settings.worldName());
            return;
        }

        IntVector3 position = settings.position();
        Location location = new Location(world, position.x() + 0.5D, position.y(), position.z() + 0.5D, settings.yaw(), 0.0F);
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(Component.text(settings.text()));
            entity.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setLineWidth(160);
            entity.setPersistent(false);
        });
        textDisplayId = display.getUniqueId();
    }

    public void shutdown() {
        removeTextDisplay();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof TextDisplay display)) {
            return;
        }
        if (textDisplayId == null || !display.getUniqueId().equals(textDisplayId)) {
            return;
        }

        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    private void openMenu(Player player) {
        List<ActionButton> actions = new ArrayList<>();
        actions.add(ActionButton.create(
                Component.text("セッションを作成"),
                Component.text("新しいAziRougeセッションを作成します。"),
                160,
                DialogAction.commandTemplate("/azirouge session create")
        ));

        GameSession session = plugin.gameSessionManager().sessionForPlayer(player.getUniqueId()).orElse(null);
        if (session != null && (session.state() == SessionState.LOBBY || session.state() == SessionState.BETWEEN_ROUNDS)) {
            for (int depth : plugin.settings().gui().depthOptions()) {
                actions.add(ActionButton.create(
                        Component.text("ラウンド開始 depth " + depth),
                        Component.text("現在のセッションで depth " + depth + " のラウンドを開始します。"),
                        160,
                        DialogAction.commandTemplate("/azirouge round start default " + depth)
                ));
            }
        } else if (session == null) {
            actions.add(ActionButton.create(
                    Component.text("セッション一覧"),
                    Component.text("参加できるセッションをチャットに表示します。"),
                    160,
                    DialogAction.commandTemplate("/azirouge session list")
            ));
        }

        ActionButton exit = ActionButton.create(
                Component.text("閉じる"),
                Component.text("何もしません。"),
                80,
                null
        );
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("AziRouge メニュー"))
                        .body(List.of(DialogBody.plainMessage(Component.text(menuDescription(session)), 260)))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .build())
                .type(DialogType.multiAction(actions)
                        .columns(1)
                        .exitAction(exit)
                        .build()));
        player.showDialog(dialog);
    }

    private String menuDescription(GameSession session) {
        if (session == null) {
            return "セッションを作成するか、セッション一覧を確認してください。";
        }
        return "現在のセッション: " + session.sessionId()
                + "\n状態: " + session.state()
                + "\n共有資金: " + session.sharedBalance()
                + "\nラウンド開始時は depth を選択してください。";
    }

    private void removeTextDisplay() {
        if (textDisplayId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(textDisplayId);
        if (entity != null) {
            entity.remove();
        }
        textDisplayId = null;
    }
}
