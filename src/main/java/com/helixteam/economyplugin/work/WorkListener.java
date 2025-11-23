package com.helixteam.economyplugin.work;

import com.helixteam.economyplugin.EconomyPlugin;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Listener para acciones de trabajo: break, place, interaction, kill
 */
public class WorkListener implements Listener {

    private final WorkPointManager manager;
    private final EconomyPlugin plugin;

    public WorkListener(WorkPointManager manager, EconomyPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        var player = event.getPlayer();
        int points = manager.getMaterialReward("break", event.getBlock().getType());
        if (points > 0) {
            // cooldown per player+action to avoid farming abuse
            String key = "break:" + event.getBlock().getType().name();
            if (manager.tryConsumeCooldown(player.getUniqueId(), key)) {
                plugin.getEconomy().depositPlayer(player, points);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        var player = event.getPlayer();
        int points = manager.getMaterialReward("place", event.getBlock().getType());
        if (points > 0) {
            String key = "place:" + event.getBlock().getType().name();
            if (manager.tryConsumeCooldown(player.getUniqueId(), key)) {
                plugin.getEconomy().depositPlayer(player, points);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only consider main hand right-click block interactions
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getClickedBlock() == null) return;
        var player = event.getPlayer();
        int points = manager.getMaterialReward("interaction", event.getClickedBlock().getType());
        if (points > 0) {
            String key = "interaction:" + event.getClickedBlock().getType().name();
            if (manager.tryConsumeCooldown(player.getUniqueId(), key)) {
                plugin.getEconomy().depositPlayer(player, points);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        var killer = event.getEntity().getKiller();
        if (killer == null) return;
        EntityType type = event.getEntityType();
        int points = manager.getKillReward(type);
        if (points > 0) {
            String key = "kill:" + type.name();
            if (manager.tryConsumeCooldown(killer.getUniqueId(), key)) {
                plugin.getEconomy().depositPlayer(killer, points);
            }
        }
    }
}
