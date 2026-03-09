package com.Shiratori.GeoCompass.command;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GeoCompassCommand implements CommandExecutor, TabCompleter {

    private final GeoCompassPlugin plugin;

    public GeoCompassCommand(GeoCompassPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.getLangService().get("command.help"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "give" -> handleGive(sender, args);
            case "reload" -> handleReload(sender);
            case "stats" -> handleStats(sender, args);
            case "gui" -> handleGui(sender);
            default -> {
                sender.sendMessage(plugin.getLangService().get("command.unknown-sub"));
                yield true;
            }
        };
    }

    private boolean handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLangService().get("command.player-only"));
            return true;
        }
        if (!player.hasPermission("geocompass.use")) {
            sender.sendMessage(plugin.getLangService().get("error.no-permission"));
            return true;
        }

        plugin.getTrackingService().openTargetGui(player);
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("geocompass.admin.give")) {
            sender.sendMessage(plugin.getLangService().get("error.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.getLangService().get("command.give-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getLangService().get("error.player-offline"));
            return true;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException ignored) {
                sender.sendMessage(plugin.getLangService().get("error.amount-number"));
                return true;
            }
        }

        target.getInventory().addItem(plugin.getCompassItemService().createCompass(amount));
        sender.sendMessage(plugin.getLangService().format("command.give-success", Map.of(
                "player", target.getName(),
                "amount", String.valueOf(amount)
        )));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("geocompass.admin.reload")) {
            sender.sendMessage(plugin.getLangService().get("error.no-permission"));
            return true;
        }

        plugin.reloadAll();
        sender.sendMessage(plugin.getLangService().get("command.reload-success"));
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.getLangService().get("error.player-offline"));
                return true;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.getLangService().get("command.stats-console-usage"));
                return true;
            }
            target = p;
        }

        int count = plugin.getDiscoveryService().getDiscoveryCount(target.getUniqueId());
        sender.sendMessage(plugin.getLangService().format("command.stats-result", Map.of(
                "player", target.getName(),
                "count", String.valueOf(count)
        )));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.add("give");
            suggestions.add("reload");
            suggestions.add("stats");
            suggestions.add("gui");
            return filter(suggestions, args[0]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("stats"))) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(player.getName());
            }
            return filter(suggestions, args[1]);
        }

        return suggestions;
    }

    private List<String> filter(List<String> source, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String item : source) {
            if (item.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(item);
            }
        }
        return result;
    }
}
