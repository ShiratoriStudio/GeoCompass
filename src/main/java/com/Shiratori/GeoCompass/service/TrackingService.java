package com.Shiratori.GeoCompass.service;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TrackingService {

    private final GeoCompassPlugin plugin;
    private BukkitTask task;
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentTrackingUntil = new ConcurrentHashMap<>();
    private final Map<World.Environment, Set<Material>> envMineralTemplate = new EnumMap<>(World.Environment.class);
    private final Map<UUID, Material> playerTargetMode = new ConcurrentHashMap<>();
    private final Map<UUID, ScanCache> scanCache = new ConcurrentHashMap<>();
    private int playerCursor = 0;

    public TrackingService(GeoCompassPlugin plugin) {
        this.plugin = plugin;
        reloadTemplates();
    }

    public void reloadTemplates() {
        envMineralTemplate.clear();
        envMineralTemplate.put(World.Environment.NORMAL, toMaterialSet(plugin.getConfig().getStringList("tracking.dimension-defaults.overworld")));
        envMineralTemplate.put(World.Environment.NETHER, toMaterialSet(plugin.getConfig().getStringList("tracking.dimension-defaults.nether")));
        envMineralTemplate.put(World.Environment.THE_END, toMaterialSet(plugin.getConfig().getStringList("tracking.dimension-defaults.end")));
    }

    public void start() {
        stop();
        int interval = Math.max(10, plugin.getConfig().getInt("tracking.check-interval", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        recentTrackingUntil.clear();
        scanCache.clear();
        for (Map.Entry<UUID, BossBar> entry : bossBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        bossBars.clear();
    }

    private void tick() {
        List<Player> all = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (all.isEmpty()) {
            return;
        }

        int budget = Math.max(1, plugin.getConfig().getInt("tracking.players-per-tick", 5));
        for (int i = 0; i < budget; i++) {
            if (all.isEmpty()) {
                return;
            }
            if (playerCursor >= all.size()) {
                playerCursor = 0;
            }
            Player player = all.get(playerCursor++);
            processPlayer(player);
        }
    }

    private void processPlayer(Player player) {
        if (!player.hasPermission("geocompass.use")) {
            hideBossbar(player);
            return;
        }
        if (!isHoldingGeoCompass(player)) {
            hideBossbar(player);
            return;
        }

        Target target = findNearestTarget(player);
        if (target == null) {
            hideBossbar(player);
            return;
        }

        if (!hadRecentTracking(player)) {
            Sound sound = resolveFoundSound();
            if (sound != null) {
                player.playSound(player.getLocation(), sound, 0.9f, 1.2f);
            }
        }

        markRecentTracking(player);
        sendDirection(player, target);
    }

    public void openTargetGui(Player player) {
        Set<Material> targets = resolveTargetMineralsSet(player.getWorld());
        if (targets.isEmpty()) {
            player.sendMessage(plugin.getLangService().get("tracking.no-targets"));
            return;
        }

        List<Material> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparing(Enum::name));

        int size = Math.min(54, ((sorted.size() + 1 + 8) / 9) * 9);
        size = Math.max(size, 9);
        Inventory inv = Bukkit.createInventory(null, size, Component.text(getGuiTitle()));

        ItemStack auto = new ItemStack(Material.COMPASS);
        ItemMeta autoMeta = auto.getItemMeta();
        autoMeta.displayName(Component.text(plugin.getLangService().get("tracking.gui-auto")));
        auto.setItemMeta(autoMeta);
        inv.setItem(0, auto);

        int slot = 1;
        for (Material material : sorted) {
            if (slot >= size) {
                break;
            }
            ItemStack stack = new ItemStack(material);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(Component.text(pretty(material)));
            stack.setItemMeta(meta);
            inv.setItem(slot++, stack);
        }

        player.openInventory(inv);
    }

    public boolean handleGuiClick(Player player, ItemStack clicked) {
        if (clicked == null || clicked.getType() == Material.AIR) {
            return true;
        }
        if (clicked.getType() == Material.COMPASS) {
            playerTargetMode.remove(player.getUniqueId());
            player.sendMessage(plugin.getLangService().get("tracking.target-auto-selected"));
            return true;
        }

        Set<Material> targets = resolveTargetMineralsSet(player.getWorld());
        if (!targets.contains(clicked.getType())) {
            return true;
        }

        playerTargetMode.put(player.getUniqueId(), clicked.getType());
        player.sendMessage(plugin.getLangService().format("tracking.target-selected", Map.of("target", pretty(clicked.getType()))));
        return true;
    }

    public boolean isHoldingGeoCompass(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return plugin.getCompassItemService().isGeoCompass(main) || plugin.getCompassItemService().isGeoCompass(off);
    }

    public void markRecentTracking(Player player) {
        long seconds = Math.max(1, plugin.getConfig().getLong("tracking.recent-valid-seconds", 30));
        recentTrackingUntil.put(player.getUniqueId(), Instant.now().getEpochSecond() + seconds);
    }

    public boolean hadRecentTracking(Player player) {
        long now = Instant.now().getEpochSecond();
        long until = recentTrackingUntil.getOrDefault(player.getUniqueId(), 0L);
        return now <= until;
    }

    private void sendDirection(Player player, Target target) {
        String direction = buildDirection(player.getLocation(), target.location());
        String targetName = pretty(target.material());
        String text = plugin.getLangService().format("tracking.found", Map.of(
                "direction", direction,
                "distance", String.valueOf(target.distance()),
                "target", targetName
        ));

        String mode = plugin.getConfig().getString("tracking.display.mode", "ACTIONBAR").toUpperCase(Locale.ROOT);
        Component message = Component.text(text);

        switch (mode) {
            case "TITLE" -> player.sendTitlePart(net.kyori.adventure.title.TitlePart.TITLE, message);
            case "BOSSBAR" -> showBossbar(player, message, target.distance());
            default -> {
                hideBossbar(player);
                player.sendActionBar(message);
            }
        }
    }

    private void showBossbar(Player player, Component text, int distance) {
        float progress = Math.max(0.05f, Math.min(1.0f, 1.0f - (distance / (float) Math.max(1, plugin.getConfig().getInt("tracking.range", 15)))));
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), id -> BossBar.bossBar(Component.text(""), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS));
        bar.name(text);
        bar.progress(progress);
        bar.color(progress > 0.66 ? BossBar.Color.GREEN : progress > 0.33 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        player.showBossBar(bar);
    }

    private void hideBossbar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private Target findNearestTarget(Player player) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null || !isWorldEnabled(world.getName())) {
            return null;
        }

        Set<Material> targets = resolveTargetMineralsSet(world);
        Material modeTarget = playerTargetMode.get(player.getUniqueId());
        if (modeTarget != null) {
            if (targets.contains(modeTarget)) {
                targets = Set.of(modeTarget);
            } else {
                playerTargetMode.remove(player.getUniqueId());
            }
        }

        if (targets.isEmpty()) {
            return null;
        }

        int range = Math.max(4, plugin.getConfig().getInt("tracking.range", 15));
        int grid = Math.max(1, plugin.getConfig().getInt("tracking.cache-grid-size", 4));
        long cacheMs = Math.max(200L, plugin.getConfig().getLong("tracking.scan-cache-ms", 1500L));
        int gx = base.getBlockX() / grid;
        int gy = base.getBlockY() / grid;
        int gz = base.getBlockZ() / grid;
        long now = System.currentTimeMillis();

        ScanCache cached = scanCache.get(player.getUniqueId());
        if (cached != null
                && cached.worldName().equals(world.getName())
                && cached.gridX() == gx
                && cached.gridY() == gy
                && cached.gridZ() == gz
                && now <= cached.expiresAt()) {
            return cached.target();
        }

        Target found = scanByChunkSnapshots(world, base, range, targets);
        scanCache.put(player.getUniqueId(), new ScanCache(world.getName(), gx, gy, gz, now + cacheMs, found));
        return found;
    }

    private Target scanByChunkSnapshots(World world, Location base, int range, Set<Material> targets) {
        int minY = Math.max(world.getMinHeight(), base.getBlockY() - range);
        int maxY = Math.min(world.getMaxHeight() - 1, base.getBlockY() + range);
        int stepXZ = Math.max(1, plugin.getConfig().getInt("tracking.sampling.step-xz", 2));
        int stepY = Math.max(1, plugin.getConfig().getInt("tracking.sampling.step-y", 2));

        int centerChunkX = base.getBlockX() >> 4;
        int centerChunkZ = base.getBlockZ() >> 4;
        int chunkRadius = Math.max(1, (range + 15) >> 4);

        double best = Double.MAX_VALUE;
        Target bestTarget = null;

        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }

                Chunk chunk = world.getChunkAt(cx, cz);
                ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
                int worldXBase = cx << 4;
                int worldZBase = cz << 4;

                for (int y = minY; y <= maxY; y += stepY) {
                    for (int x = 0; x < 16; x += stepXZ) {
                        for (int z = 0; z < 16; z += stepXZ) {
                            Material material = snapshot.getBlockType(x, y, z);
                            if (!targets.contains(material)) {
                                continue;
                            }

                            double wx = worldXBase + x + 0.5;
                            double wy = y + 0.5;
                            double wz = worldZBase + z + 0.5;
                            double dist = distanceSquared(base.getX(), base.getY(), base.getZ(), wx, wy, wz);

                            if (dist < best) {
                                best = dist;
                                Location loc = new Location(world, wx, wy, wz);
                                bestTarget = new Target(loc, (int) Math.round(Math.sqrt(dist)), material);
                            }
                        }
                    }
                }
            }
        }

        return bestTarget;
    }

    private double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean isTrackedMaterial(World world, Material material) {
        return resolveTargetMineralsSet(world).contains(material);
    }

    public List<Material> resolveTargetMinerals(World world) {
        return new ArrayList<>(resolveTargetMineralsSet(world));
    }

    public Set<Material> resolveTargetMineralsSet(World world) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("worlds.world-minerals");
        if (section != null && section.contains(world.getName())) {
            Set<Material> worldDefined = toMaterialSet(section.getStringList(world.getName()));
            if (!worldDefined.isEmpty()) {
                return worldDefined;
            }
        }

        Set<Material> byEnv = envMineralTemplate.getOrDefault(world.getEnvironment(), Set.of());
        if (!byEnv.isEmpty()) {
            return new HashSet<>(byEnv);
        }

        return toMaterialSet(plugin.getConfig().getStringList("tracking.minerals"));
    }

    private Set<Material> toMaterialSet(List<String> names) {
        Set<Material> materials = new HashSet<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    private boolean isWorldEnabled(String worldName) {
        List<String> enabled = plugin.getConfig().getStringList("worlds.enabled-worlds");
        return enabled.isEmpty() || enabled.contains(worldName);
    }

    private Sound resolveFoundSound() {
        String soundName = plugin.getConfig().getString("tracking.sounds.found", "ENTITY_EXPERIENCE_ORB_PICKUP");
        try {
            return Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            try {
                return Sound.valueOf("ENTITY_EXPERIENCE_ORB_PICKUP");
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private String buildDirection(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();

        String east = plugin.getConfig().getString("tracking.direction-labels.east", "East");
        String west = plugin.getConfig().getString("tracking.direction-labels.west", "West");
        String south = plugin.getConfig().getString("tracking.direction-labels.south", "South");
        String north = plugin.getConfig().getString("tracking.direction-labels.north", "North");

        String horizontal;
        if (Math.abs(dx) > Math.abs(dz)) {
            horizontal = dx > 0 ? "→" + east : "←" + west;
        } else {
            horizontal = dz > 0 ? "↓" + south : "↑" + north;
        }

        String vertical = Math.abs(dy) < 2 ? "=" : (dy > 0 ? "↑" : "↓");
        return horizontal + " " + vertical;
    }

    public String getGuiTitle() {
        return plugin.getLangService().get("tracking.gui-title");
    }

    private String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private record Target(Location location, int distance, Material material) {
    }

    private record ScanCache(String worldName, int gridX, int gridY, int gridZ, long expiresAt, Target target) {
    }
}
