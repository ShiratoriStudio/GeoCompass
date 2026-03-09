package com.Shiratori.GeoCompass.listener;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class CompassUseListener implements Listener {

    private final GeoCompassPlugin plugin;
    private final Map<UUID, BossBar> surveyBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> hideTasks = new ConcurrentHashMap<>();

    public CompassUseListener(GeoCompassPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerUseCompass(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("geocompass.use")) {
            return;
        }

        ItemStack item = event.getItem();
        if (!plugin.getCompassItemService().isGeoCompass(item)) {
            return;
        }

        event.setCancelled(true);

        if (player.isSneaking()) {
            plugin.getTrackingService().openTargetGui(player);
            return;
        }

        int cost = Math.max(0, plugin.getConfig().getInt("item.energy.survey-cost", 1));
        if (!plugin.getCompassItemService().consumeEnergy(player, cost)) {
            player.sendMessage(plugin.getLangService().format("item.energy-not-enough", Map.of(
                    "current", String.valueOf(plugin.getCompassItemService().getEnergy(player)),
                    "cost", String.valueOf(cost)
            )));
            return;
        }

        plugin.getSurveyService().trySurveyAsync(player, result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            FileConfiguration config = plugin.getConfig();
            String mode = config.getString("survey.display-mode", "CHAT").toUpperCase(Locale.ROOT);
            if ("BOSSBAR".equals(mode)) {
                showSurveyBossbar(player, result);
            } else if ("ACTIONBAR".equals(mode)) {
                player.sendActionBar(Component.text(result));
            } else {
                player.sendMessage(result);
            }
        }));
    }

    @EventHandler
    public void onTrackingGuiClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String currentTitle = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        String guiTitle = PlainTextComponentSerializer.plainText().serialize(Component.text(plugin.getTrackingService().getGuiTitle()));
        if (!guiTitle.equals(currentTitle)) {
            return;
        }

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (plugin.getTrackingService().handleGuiClick(player, clicked)) {
            player.closeInventory();
        }
    }

    private void showSurveyBossbar(Player player, String text) {
        int durationTicks = Math.max(20, plugin.getConfig().getInt("survey.bossbar.duration-ticks", 80));
        BossBar.Color color = parseColor(plugin.getConfig().getString("survey.bossbar.color", "BLUE"));
        BossBar.Overlay overlay = parseOverlay(plugin.getConfig().getString("survey.bossbar.overlay", "PROGRESS"));

        BossBar bar = surveyBars.computeIfAbsent(player.getUniqueId(), id -> BossBar.bossBar(Component.text(""), 1.0f, color, overlay));
        bar.name(Component.text(text));
        bar.color(color);
        bar.overlay(overlay);
        bar.progress(1.0f);
        player.showBossBar(bar);

        BukkitTask oldTask = hideTasks.remove(player.getUniqueId());
        if (oldTask != null) {
            oldTask.cancel();
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            BossBar existing = surveyBars.get(player.getUniqueId());
            if (existing != null) {
                player.hideBossBar(existing);
            }
        }, durationTicks);
        hideTasks.put(player.getUniqueId(), task);
    }

    private BossBar.Color parseColor(String input) {
        try {
            return BossBar.Color.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return BossBar.Color.BLUE;
        }
    }

    private BossBar.Overlay parseOverlay(String input) {
        try {
            return BossBar.Overlay.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
