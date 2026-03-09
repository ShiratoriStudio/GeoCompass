package com.Shiratori.GeoCompass;

import com.Shiratori.GeoCompass.command.GeoCompassCommand;
import com.Shiratori.GeoCompass.listener.CompassUseListener;
import com.Shiratori.GeoCompass.listener.DiscoveryListener;
import com.Shiratori.GeoCompass.service.CompassItemService;
import com.Shiratori.GeoCompass.service.DiscoveryService;
import com.Shiratori.GeoCompass.service.LangService;
import com.Shiratori.GeoCompass.service.SurveyService;
import com.Shiratori.GeoCompass.service.TrackingService;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class GeoCompassPlugin extends JavaPlugin {

    private NamespacedKey geocompassKey;
    private CompassItemService compassItemService;
    private SurveyService surveyService;
    private TrackingService trackingService;
    private DiscoveryService discoveryService;
    private LangService langService;

    @Override
    public void onEnable() {
        int pluginId = 30010;
        Metrics metrics = new Metrics(this, pluginId);

        saveDefaultConfig();
        ensureLangFile();

        this.geocompassKey = new NamespacedKey(this, "geocompass_item");
        this.langService = new LangService(this);
        this.compassItemService = new CompassItemService(this);
        this.surveyService = new SurveyService(this);
        this.discoveryService = new DiscoveryService(this);
        this.trackingService = new TrackingService(this);

        registerCraftingRecipe();
        registerListenersAndCommands();

        if (getConfig().getBoolean("tracking.enabled", true)) {
            trackingService.start();
        }

        getLogger().info("GeoCompass enabled.");
    }

    @Override
    public void onDisable() {
        if (trackingService != null) {
            trackingService.stop();
        }
        if (discoveryService != null) {
            discoveryService.shutdown();
        }
        getLogger().info("GeoCompass disabled.");
    }

    public void reloadAll() {
        reloadConfig();
        langService.reload();
        surveyService.clearCache();
        trackingService.reloadTemplates();

        trackingService.stop();
        if (getConfig().getBoolean("tracking.enabled", true)) {
            trackingService.start();
        }
    }

    private void ensureLangFile() {
        File file = new File(getDataFolder(), "lang.yml");
        if (!file.exists()) {
            saveResource("lang.yml", false);
        }
    }

    private void registerListenersAndCommands() {
        Bukkit.getPluginManager().registerEvents(new CompassUseListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DiscoveryListener(this), this);

        GeoCompassCommand commandExecutor = new GeoCompassCommand(this);
        if (getCommand("geocompass") != null) {
            getCommand("geocompass").setExecutor(commandExecutor);
            getCommand("geocompass").setTabCompleter(commandExecutor);
        }
    }

    private void registerCraftingRecipe() {
        FileConfiguration config = getConfig();
        if (!config.getBoolean("crafting.enabled", true)) {
            return;
        }
        compassItemService.registerRecipe();
    }

    public NamespacedKey getGeocompassKey() {
        return geocompassKey;
    }

    public CompassItemService getCompassItemService() {
        return compassItemService;
    }

    public SurveyService getSurveyService() {
        return surveyService;
    }

    public TrackingService getTrackingService() {
        return trackingService;
    }

    public DiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    public LangService getLangService() {
        return langService;
    }
}
