package com.Shiratori.GeoCompass.listener;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class DiscoveryListener implements Listener {

    private final GeoCompassPlugin plugin;

    public DiscoveryListener(GeoCompassPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("geocompass.use")) {
            return;
        }

        Material broken = event.getBlock().getType();
        if (!isTrackedOre(player, broken)) {
            return;
        }

        boolean holdingCompass = plugin.getTrackingService().isHoldingGeoCompass(player);
        boolean hadTracking = plugin.getTrackingService().hadRecentTracking(player);
        if (!holdingCompass && !hadTracking) {
            return;
        }

        plugin.getDiscoveryService().announceIfNeeded(player, broken, event.getBlock().getLocation());
    }

    private boolean isTrackedOre(Player player, Material material) {
        World world = player.getWorld();
        return plugin.getTrackingService().isTrackedMaterial(world, material);
    }
}
