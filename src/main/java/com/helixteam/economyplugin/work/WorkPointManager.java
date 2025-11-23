package com.helixteam.economyplugin.work;

import com.helixteam.economyplugin.EconomyPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Manager para puntos de trabajo configurables desde config.yml.
 * Lee `work_points.actions` y soporta acciones: break, place, interaction y kill.
 */
public class WorkPointManager {

    private final EconomyPlugin plugin;
    private final Map<String, Map<Material, Integer>> materialRewards = new HashMap<>();
    private final Map<EntityType, Integer> killRewards = new HashMap<>();
    // cooldown in seconds between awarding points for the same player+action
    private int cooldownSeconds = 5;
    // per-player per-action last timestamp (epoch seconds)
    private final ConcurrentMap<UUID, ConcurrentMap<String, Long>> lastAction = new ConcurrentHashMap<>();

    public WorkPointManager(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        materialRewards.clear();
        killRewards.clear();

        // read cooldown (seconds)
        try {
            this.cooldownSeconds = plugin.getConfig().getInt("work_points.cooldown_seconds", 5);
            if (this.cooldownSeconds < 0) this.cooldownSeconds = 0;
        } catch (Exception ignored) {
            this.cooldownSeconds = 5;
        }

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("work_points.actions");
        if (root == null) return;

        for (String actionKey : root.getKeys(false)) {
            if (actionKey == null) continue;
            String action = actionKey.toLowerCase(Locale.ROOT);
            ConfigurationSection actionSec = root.getConfigurationSection(actionKey);
            if (actionSec == null) continue;

            if (action.equals("kill")) {
                // Keys are EntityType names
                for (String entKey : actionSec.getKeys(false)) {
                    try {
                        EntityType et = EntityType.valueOf(entKey.toUpperCase(Locale.ROOT));
                        int val = actionSec.getInt(entKey, 0);
                        if (val > 0) killRewards.put(et, val);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Entidad inválida en work_points.actions.kill: " + entKey + " - se ignora");
                    }
                }
            } else {
                // Treat keys as Material
                Map<Material, Integer> map = new HashMap<>();
                for (String matKey : actionSec.getKeys(false)) {
                    try {
                        Material m = Material.valueOf(matKey.toUpperCase(Locale.ROOT));
                        int val = actionSec.getInt(matKey, 0);
                        if (val > 0) map.put(m, val);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Material inválido en work_points.actions." + action + ": " + matKey + " - se ignora");
                    }
                }
                materialRewards.put(action, map);
            }
        }
    }

    /**
     * Try to consume cooldown for a given player and action key. Returns true
     * if enough time has passed and the action can proceed (and updates the timestamp).
     */
    public boolean tryConsumeCooldown(java.util.UUID player, String actionKey) {
        if (cooldownSeconds <= 0) return true;
        long now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        var map = lastAction.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        Long last = map.get(actionKey);
        if (last == null || (now - last) >= cooldownSeconds) {
            map.put(actionKey, now);
            return true;
        }
        return false;
    }

    public int getMaterialReward(String action, Material material) {
        var map = materialRewards.get(action.toLowerCase(Locale.ROOT));
        if (map == null) return 0;
        return map.getOrDefault(material, 0);
    }

    public int getKillReward(EntityType type) {
        return killRewards.getOrDefault(type, 0);
    }
}
