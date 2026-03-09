package com.Shiratori.GeoCompass.service;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class DiscoveryService {

    private final GeoCompassPlugin plugin;
    private final BlockingQueue<DiscoveryRecord> writeQueue = new LinkedBlockingQueue<>();
    private final String jdbcUrl;
    private volatile boolean running = true;
    private Thread writerThread;

    public DiscoveryService(GeoCompassPlugin plugin) {
        this.plugin = plugin;
        File dbFile = new File(plugin.getDataFolder(), "discoveries.db");
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        ensureDatabase();
        startWriter();
    }

    public void shutdown() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
        }
        flushRemaining();
    }

    public boolean announceIfNeeded(Player player, Material ore, Location location) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("announcements.first-discovery.enabled", true)) {
            return false;
        }

        boolean repeatDiscovery = config.getBoolean("announcements.repeat-discovery", false);
        if (!repeatDiscovery && hasFound(player.getUniqueId(), ore)) {
            return false;
        }

        enqueueFound(player.getUniqueId(), ore, location);

        String message = plugin.getLangService().format("announcement.first-discovery", Map.of(
                "player", player.getName(),
                "x", String.valueOf(location.getBlockX()),
                "y", String.valueOf(location.getBlockY()),
                "z", String.valueOf(location.getBlockZ()),
                "ore", pretty(ore)
        ));

        Bukkit.getServer().sendMessage(Component.text(message));

        List<String> rewardCommands = config.getStringList("announcements.first-discovery.reward-commands");
        for (String command : rewardCommands) {
            String parsed = command
                    .replace("%player%", player.getName())
                    .replace("%ore%", ore.name())
                    .replace("%x%", String.valueOf(location.getBlockX()))
                    .replace("%y%", String.valueOf(location.getBlockY()))
                    .replace("%z%", String.valueOf(location.getBlockZ()));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        return true;
    }

    public int getDiscoveryCount(UUID uuid) {
        String sql = "SELECT COUNT(DISTINCT ore) FROM discoveries WHERE uuid = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to query discovery count: " + e.getMessage());
            return 0;
        }
    }

    private boolean hasFound(UUID uuid, Material ore) {
        String sql = "SELECT 1 FROM discoveries WHERE uuid = ? AND ore = ? LIMIT 1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ore.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to query discovery existence: " + e.getMessage());
            return false;
        }
    }

    private void enqueueFound(UUID uuid, Material ore, Location location) {
        String world = location.getWorld() == null ? "unknown" : location.getWorld().getName();
        writeQueue.offer(new DiscoveryRecord(
                uuid.toString(),
                ore.name(),
                world,
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                Instant.now().toString()
        ));
    }

    private void ensureDatabase() {
        String createSql = """
                CREATE TABLE IF NOT EXISTS discoveries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    ore TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    time TEXT NOT NULL
                );
                """;
        String idxUuid = "CREATE INDEX IF NOT EXISTS idx_discoveries_uuid ON discoveries(uuid);";
        String idxOre = "CREATE INDEX IF NOT EXISTS idx_discoveries_ore ON discoveries(ore);";
        String idxTime = "CREATE INDEX IF NOT EXISTS idx_discoveries_time ON discoveries(time);";
        String idxUuidOre = "CREATE INDEX IF NOT EXISTS idx_discoveries_uuid_ore ON discoveries(uuid, ore);";

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(createSql);
            statement.execute(idxUuid);
            statement.execute(idxOre);
            statement.execute(idxTime);
            statement.execute(idxUuidOre);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize discoveries database: " + e.getMessage());
        }
    }

    private void startWriter() {
        writerThread = new Thread(() -> {
            while (running) {
                try {
                    DiscoveryRecord first = writeQueue.take();
                    List<DiscoveryRecord> batch = new ArrayList<>();
                    batch.add(first);
                    writeQueue.drainTo(batch, 100);
                    writeBatch(batch);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("Discovery writer loop error: " + e.getMessage());
                }
            }
        }, "GeoCompass-DiscoveryWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void flushRemaining() {
        List<DiscoveryRecord> remain = new ArrayList<>();
        writeQueue.drainTo(remain);
        if (!remain.isEmpty()) {
            writeBatch(remain);
        }
    }

    private void writeBatch(List<DiscoveryRecord> batch) {
        String insertSql = "INSERT INTO discoveries(uuid, ore, world, x, y, z, time) VALUES(?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(insertSql)) {
            connection.setAutoCommit(false);
            for (DiscoveryRecord record : batch) {
                ps.setString(1, record.uuid());
                ps.setString(2, record.ore());
                ps.setString(3, record.world());
                ps.setInt(4, record.x());
                ps.setInt(5, record.y());
                ps.setInt(6, record.z());
                ps.setString(7, record.time());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to write discovery batch: " + e.getMessage());
        }
    }

    private String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            list.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return String.join(" ", list);
    }

    private record DiscoveryRecord(String uuid, String ore, String world, int x, int y, int z, String time) {
    }
}
