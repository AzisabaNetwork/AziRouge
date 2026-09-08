package net.azisaba.aziRouge.game;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.azisaba.aziRouge.AziRouge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConfirmationService implements Listener {
    private static final long TIMEOUT_MILLIS = 15_000L;

    private final AziRouge plugin;
    private final Map<UUID, PendingConfirmation> confirmations = new HashMap<>();

    public ConfirmationService(AziRouge plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, String description, Runnable onConfirm) {
        long expiresAt = System.currentTimeMillis() + TIMEOUT_MILLIS;
        confirmations.put(player.getUniqueId(), new PendingConfirmation(onConfirm, expiresAt));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(message("confirmation.title", "Confirm"))
                        .body(List.of(DialogBody.plainMessage(Component.text(description), 260)))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .build())
                .type(DialogType.confirmation(
                        action("confirmation.yes", "Yes", true),
                        action("confirmation.no", "No", false)
                )));
        player.showDialog(dialog);
        Bukkit.getScheduler().runTaskLater(plugin, () -> expire(player.getUniqueId(), expiresAt), TIMEOUT_MILLIS / 50L);
    }

    public boolean confirm(Player player, boolean accepted) {
        PendingConfirmation pending = confirmations.remove(player.getUniqueId());
        if (pending == null || pending.isExpired()) {
            player.sendMessage(plugin.messages().prefixed(
                    "confirmation.expired",
                    "&7Confirmation expired. Try the action again."
            ));
            return true;
        }
        if (!accepted) {
            player.sendMessage(plugin.messages().prefixed(
                    "confirmation.cancelled",
                    "&7Action cancelled."
            ));
            return true;
        }
        pending.onConfirm().run();
        return true;
    }

    private ActionButton action(String key, String fallback, boolean accepted) {
        return ActionButton.create(
                message(key, fallback),
                message(key + "-tooltip", fallback),
                120,
                DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player player) {
                                confirm(player, accepted);
                            }
                        },
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(ClickCallback.DEFAULT_LIFETIME)
                                .build()
                )
        );
    }

    private Component message(String key, String fallback) {
        return Component.text(plugin.messages().text(key, fallback));
    }

    private void expire(UUID playerId, long expiresAt) {
        PendingConfirmation pending = confirmations.get(playerId);
        if (pending != null && pending.expiresAtMillis() == expiresAt && pending.isExpired()) {
            confirmations.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(plugin.messages().prefixed(
                        "confirmation.expired",
                        "&7Confirmation expired. Try the action again."
                ));
            }
        }
    }

    private record PendingConfirmation(Runnable onConfirm, long expiresAtMillis) {
        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }
}
