package com.Shiratori.GeoCompass.service;

import com.Shiratori.GeoCompass.GeoCompassPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CompassItemService {

    private final GeoCompassPlugin plugin;

    public CompassItemService(GeoCompassPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack createCompass(int amount) {
        ItemStack item = new ItemStack(Material.COMPASS, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("item.name", "&6GeoCompass");
        meta.displayName(Component.text(color(name)));

        List<String> loreConfig = plugin.getConfig().getStringList("item.lore");
        List<Component> lore = new ArrayList<>();
        if (loreConfig.isEmpty()) {
            lore.add(Component.text("§7Right-click to survey nearby blocks"));
            lore.add(Component.text("§7Hold it to track rare minerals"));
        } else {
            for (String line : loreConfig) {
                lore.add(Component.text(color(line)));
            }
        }

        int maxEnergy = maxEnergy();
        lore.add(Component.text(formatEnergyLore(maxEnergy, maxEnergy)));
        meta.lore(lore);

        int customModelData = plugin.getConfig().getInt("item.custom-model-data", 0);
        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.getGeocompassKey(), PersistentDataType.BYTE, (byte) 1);
        pdc.set(new NamespacedKey(plugin, "energy"), PersistentDataType.INTEGER, maxEnergy());

        item.setItemMeta(meta);
        return item;
    }

    public boolean isGeoCompass(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        Byte flag = meta.getPersistentDataContainer().get(plugin.getGeocompassKey(), PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    public int getEnergy(ItemStack itemStack) {
        if (!isGeoCompass(itemStack)) {
            return 0;
        }
        ItemMeta meta = itemStack.getItemMeta();
        Integer energy = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "energy"), PersistentDataType.INTEGER);
        return energy == null ? maxEnergy() : Math.max(0, energy);
    }

    public int getEnergy(Player player) {
        ItemStack item = getHoldingCompassItem(player);
        if (item == null) {
            return 0;
        }
        return getEnergy(item);
    }

    public boolean consumeEnergy(Player player, int amount) {
        ItemStack item = getHoldingCompassItem(player);
        if (item == null) {
            return false;
        }

        int cost = Math.max(0, amount);
        int current = getEnergy(item);
        if (current < cost) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        int next = current - cost;
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "energy"), PersistentDataType.INTEGER, next);
        updateEnergyLore(meta, next, maxEnergy());
        item.setItemMeta(meta);

        if (player.getInventory().getItemInMainHand() == item) {
            player.getInventory().setItem(EquipmentSlot.HAND, item);
        } else if (player.getInventory().getItemInOffHand() == item) {
            player.getInventory().setItem(EquipmentSlot.OFF_HAND, item);
        }
        return true;
    }

    public int maxEnergy() {
        return Math.max(1, plugin.getConfig().getInt("item.energy.max", 100));
    }

    private ItemStack getHoldingCompassItem(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isGeoCompass(main)) {
            return main;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isGeoCompass(off)) {
            return off;
        }
        return null;
    }

    public void registerRecipe() {
        List<String> shape = plugin.getConfig().getStringList("crafting.shape");
        if (shape.size() != 3) {
            plugin.getLogger().warning("Invalid crafting.shape, must contain 3 lines.");
            return;
        }

        NamespacedKey recipeKey = new NamespacedKey(plugin, "geocompass_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createCompass(1));
        recipe.shape(shape.get(0), shape.get(1), shape.get(2));

        ConfigurationSection ingredients = plugin.getConfig().getConfigurationSection("crafting.ingredients");
        if (ingredients == null) {
            plugin.getLogger().warning("crafting.ingredients missing in config.");
            return;
        }

        for (String key : ingredients.getKeys(false)) {
            if (key.length() != 1) {
                continue;
            }
            String materialName = ingredients.getString(key, "").toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("Unknown material in recipe: " + materialName);
                continue;
            }
            recipe.setIngredient(key.charAt(0), new RecipeChoice.MaterialChoice(material));
        }

        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
    }

    private void updateEnergyLore(ItemMeta meta, int current, int max) {
        List<Component> lore = meta.lore();
        List<Component> mutable = lore == null ? new ArrayList<>() : new ArrayList<>(lore);

        String prefix = color(plugin.getConfig().getString("item.energy.lore-prefix", "&7Energy: "));
        String marker = stripColor(prefix).toLowerCase(Locale.ROOT).trim();

        // Remove ALL previous energy lines (including older buggy duplicates).
        mutable.removeIf(component -> {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(component)
                    .toLowerCase(Locale.ROOT)
                    .trim();
            return plain.startsWith(marker) || plain.matches(".*\\d+\\s*/\\s*\\d+$");
        });

        mutable.add(Component.text(formatEnergyLore(current, max)));
        meta.lore(mutable);
    }

    private String formatEnergyLore(int current, int max) {
        String prefix = color(plugin.getConfig().getString("item.energy.lore-prefix", "&7Energy: "));
        return prefix + current + "/" + max;
    }

    private String stripColor(String input) {
        return input.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }

    private String color(String input) {
        return input.replace('&', '§');
    }
}
