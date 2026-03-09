package com.Shiratori.GeoCompass.service;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;

public class LangService {

    private final GeoCompassPlugin plugin;
    private FileConfiguration lang;

    public LangService(GeoCompassPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        this.lang = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String path) {
        return color(lang.getString(path, "<missing-lang:" + path + ">"));
    }

    public String format(String path, Map<String, String> placeholders) {
        String text = get(path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return text;
    }

    public String color(String input) {
        return input.replace('&', '§');
    }
}
