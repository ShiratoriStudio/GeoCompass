package com.Shiratori.GeoCompass.service;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

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
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SurveyService {

    private final GeoCompassPlugin plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static final Set<Material> IGNORED_BLOCKS = Set.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.GRASS,
            Material.TALL_GRASS,
            Material.FERN,
            Material.LARGE_FERN,
            Material.DANDELION,
            Material.POPPY
    );

    public SurveyService(GeoCompassPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearCache() {
        cache.clear();
    }

    public void trySurveyAsync(Player player, Consumer<String> callback) {
        FileConfiguration config = plugin.getConfig();
        int cooldownSec = config.getInt("survey.cooldown", 10);

        if (!player.hasPermission("geocompass.bypass.cooldown")) {
            long now = Instant.now().getEpochSecond();
            long next = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < next) {
                callback.accept(plugin.getLangService().format("survey.cooldown", Map.of("seconds", String.valueOf(next - now))));
                return;
            }
            cooldowns.put(player.getUniqueId(), now + cooldownSec);
        }

        int radius = Math.max(0, Math.min(4, config.getInt("survey.radius", 1)));
        int maxResults = Math.max(1, config.getInt("survey.max-results", 3));
        long ttlMs = Math.max(1000L, config.getLong("survey.cache-ttl-ms", 30000L));
        Set<Material> probeBlocks = resolveProbeBlocks(config);

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            callback.accept(plugin.getLangService().get("survey.world-unavailable"));
            return;
        }

        int centerX = loc.getChunk().getX();
        int centerZ = loc.getChunk().getZ();
        String probeHash = probeBlocks.stream().map(Material::name).sorted().collect(Collectors.joining(","));
        String cacheKey = world.getName() + ":" + centerX + ":" + centerZ + ":" + radius + ":" + maxResults + ":" + probeHash;

        CacheEntry cached = cache.get(cacheKey);
        long nowMs = System.currentTimeMillis();
        if (cached != null && nowMs - cached.time <= ttlMs) {
            callback.accept(cached.message);
            return;
        }

        List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                snapshots.add(chunk.getChunkSnapshot(true, false, false));
            }
        }

        if (snapshots.isEmpty()) {
            callback.accept(plugin.getLangService().get("survey.no-data"));
            return;
        }

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<Material, Integer> counts = new EnumMap<>(Material.class);
            for (ChunkSnapshot snapshot : snapshots) {
                scanSnapshot(snapshot, minY, maxY, counts, probeBlocks);
            }

            String message;
            if (counts.isEmpty()) {
                message = plugin.getLangService().get("survey.no-data");
            } else {
                List<Map.Entry<Material, Integer>> sorted = new ArrayList<>(counts.entrySet());
                sorted.sort(Comparator.comparingInt(Map.Entry<Material, Integer>::getValue).reversed());

                List<String> top = new ArrayList<>();
                for (int i = 0; i < Math.min(maxResults, sorted.size()); i++) {
                    Map.Entry<Material, Integer> e = sorted.get(i);
                    top.add(formatMaterial(e.getKey()) + " x" + e.getValue());
                }
                message = plugin.getLangService().format("survey.result", Map.of("items", String.join("§7，§a", top)));
            }

            cache.put(cacheKey, new CacheEntry(message, System.currentTimeMillis()));
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(message));
        });
    }

    private void scanSnapshot(ChunkSnapshot snapshot, int minY, int maxY, Map<Material, Integer> counts, Set<Material> probeBlocks) {
        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material material = snapshot.getBlockType(x, y, z);
                    if (IGNORED_BLOCKS.contains(material)) {
                        continue;
                    }
                    if (!probeBlocks.contains(material)) {
                        continue;
                    }
                    counts.merge(material, 1, Integer::sum);
                }
            }
        }
    }

    private Set<Material> resolveProbeBlocks(FileConfiguration config) {
        List<String> configured = config.getStringList("survey.probe-blocks");
        Set<Material> resolved = new HashSet<>();

        for (String raw : configured) {
            Material m = Material.matchMaterial(raw);
            if (m != null) {
                resolved.add(m);
            }
        }

        if (!resolved.isEmpty()) {
            return resolved;
        }

        Set<Material> fallback = new HashSet<>();
        for (Material material : Material.values()) {
            if (isDefaultIncluded(material)) {
                fallback.add(material);
            }
        }
        return fallback;
    }

    private boolean isDefaultIncluded(Material material) {
        String name = material.name();
        return name.contains("ORE")
                || name.contains("STONE")
                || name.contains("DEEPSLATE")
                || name.contains("DIRT")
                || name.contains("GRANITE")
                || name.contains("DIORITE")
                || name.contains("ANDESITE")
                || name.contains("NETHERRACK")
                || name.contains("END_STONE");
    }

    private String formatMaterial(Material material) {
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

    private record CacheEntry(String message, long time) {
    }
}
