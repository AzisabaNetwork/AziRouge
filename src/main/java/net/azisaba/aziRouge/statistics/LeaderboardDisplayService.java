package net.azisaba.aziRouge.statistics;

import net.azisaba.aziRouge.AziRouge;
import net.azisaba.aziRouge.config.LeaderboardDisplaySettings;
import net.azisaba.aziRouge.config.LeaderboardSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LeaderboardDisplayService {
    private static final String PERIOD_KEY = "leaderboard_period";

    private final AziRouge plugin;
    private final NamespacedKey periodKey;
    private final Map<RankingPeriod, TextDisplay> displays = new EnumMap<>(RankingPeriod.class);
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private BukkitTask refreshTask;
    private long lastFailureLogMillis;

    public LeaderboardDisplayService(AziRouge plugin) {
        this.plugin = plugin;
        this.periodKey = new NamespacedKey(plugin, PERIOD_KEY);
    }

    public void reload() {
        restart();
    }

    public void refreshNow() {
        refresh();
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        removeManagedDisplays();
    }

    private void restart() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        removeManagedDisplays();

        LeaderboardSettings settings = plugin.settings().leaderboard();
        if (!settings.enabled()) {
            return;
        }
        ensureDisplays();
        refresh();
        long intervalTicks = Math.max(20L, settings.updateIntervalSeconds() * 20L);
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, intervalTicks, intervalTicks);
    }

    private void refresh() {
        if (!plugin.settings().leaderboard().enabled() || !refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        ensureDisplays();
        plugin.statisticsService().rankings().whenComplete((rankings, error) -> {
            if (!plugin.isEnabled()) {
                refreshInProgress.set(false);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (error != null || !plugin.statisticsService().isAvailable()) {
                        if (error != null) {
                            logRefreshFailure(error);
                        }
                        renderUnavailable();
                    } else {
                        render(rankings);
                    }
                } finally {
                    refreshInProgress.set(false);
                }
            });
        });
    }

    private void ensureDisplays() {
        LeaderboardSettings settings = plugin.settings().leaderboard();
        for (RankingPeriod period : RankingPeriod.values()) {
            LeaderboardDisplaySettings displaySettings = settings.displays().get(period);
            if (displaySettings == null) {
                continue;
            }
            TextDisplay current = displays.get(period);
            if (current != null && current.isValid()) {
                continue;
            }
            World world = Bukkit.getWorld(displaySettings.world());
            if (world == null) {
                logWorldMissing(displaySettings.world());
                continue;
            }

            Location location = new Location(
                    world,
                    displaySettings.x(),
                    displaySettings.y(),
                    displaySettings.z(),
                    displaySettings.yaw(),
                    displaySettings.pitch()
            );
            TextDisplay display = world.spawn(location, TextDisplay.class, spawned -> {
                spawned.setGravity(false);
                spawned.setInvulnerable(true);
                spawned.setPersistent(false);
                spawned.setBillboard(Display.Billboard.FIXED);
                spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
                spawned.setLineWidth(280);
                spawned.setShadowed(true);
                spawned.setSeeThrough(false);
                spawned.setViewRange(64.0F);
                spawned.getPersistentDataContainer().set(periodKey, PersistentDataType.STRING, period.name());
                spawned.text(unavailableText(displaySettings.title()));
            });
            displays.put(period, display);
        }
    }

    private void render(Map<RankingPeriod, List<RankingEntry>> rankings) {
        ensureDisplays();
        LeaderboardSettings settings = plugin.settings().leaderboard();
        for (RankingPeriod period : RankingPeriod.values()) {
            TextDisplay display = displays.get(period);
            LeaderboardDisplaySettings displaySettings = settings.displays().get(period);
            if (display == null || !display.isValid() || displaySettings == null) {
                continue;
            }
            List<RankingEntry> entries = rankings.getOrDefault(period, List.of());
            display.text(rankingText(displaySettings.title(), entries));
        }
    }

    private Component rankingText(String title, List<RankingEntry> entries) {
        Component text = Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD);
        if (entries.isEmpty()) {
            return text.append(Component.newline())
                    .append(Component.text("まだ記録がありません", NamedTextColor.GRAY));
        }

        int position = 1;
        for (RankingEntry entry : entries) {
            NamedTextColor rankColor = switch (position) {
                case 1 -> NamedTextColor.YELLOW;
                case 2 -> NamedTextColor.WHITE;
                case 3 -> NamedTextColor.GOLD;
                default -> NamedTextColor.GRAY;
            };
            text = text.append(Component.newline())
                    .append(Component.text(position + ". ", rankColor))
                    .append(Component.text(entry.playerName(), NamedTextColor.AQUA))
                    .append(Component.text("  Round " + entry.maxRound(), NamedTextColor.WHITE));
            position++;
        }
        return text;
    }

    private void renderUnavailable() {
        LeaderboardSettings settings = plugin.settings().leaderboard();
        for (RankingPeriod period : RankingPeriod.values()) {
            TextDisplay display = displays.get(period);
            LeaderboardDisplaySettings displaySettings = settings.displays().get(period);
            if (display != null && display.isValid() && displaySettings != null) {
                display.text(unavailableText(displaySettings.title()));
            }
        }
    }

    private Component unavailableText(String title) {
        return Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("ランキングを取得できません", NamedTextColor.RED));
    }

    private void removeManagedDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(periodKey, PersistentDataType.STRING)) {
                    display.remove();
                }
            }
        }
        displays.clear();
    }

    private void logWorldMissing(String worldName) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMillis >= 60_000L) {
            lastFailureLogMillis = now;
            plugin.getLogger().warning("Leaderboard world is not loaded: " + worldName);
        }
    }

    private void logRefreshFailure(Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMillis >= 60_000L) {
            lastFailureLogMillis = now;
            Throwable cause = error.getCause() == null ? error : error.getCause();
            plugin.getLogger().warning("Failed to refresh leaderboards: " + cause.getMessage());
        }
    }
}
