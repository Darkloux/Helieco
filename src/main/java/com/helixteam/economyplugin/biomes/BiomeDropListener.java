package com.helixteam.economyplugin.biomes;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Random;

/**
 * Listener que aplica las reglas dinámicas cargadas por BiomeDropManager.
 * Actualmente implementa BlockBreakEvent como ejemplo; puede ampliarse.
 */
public class BiomeDropListener implements Listener {

    private final BiomeDropManager manager;
    private final Random random = new Random();

    public BiomeDropListener(BiomeDropManager manager) {
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        var block = event.getBlock();
        var player = event.getPlayer();

        var biome = block.getBiome();
        var material = block.getType();

        var opt = manager.getRule(biome, material);
        if (opt.isEmpty()) return;

        BiomeDropManager.DropRule rule = opt.get();

        // Desactivamos los drops automáticos y gestionamos manualmente según reglas
        event.setDropItems(false);

        Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand());
        for (ItemStack drop : drops) {
            int baseAmount = drop.getAmount();

            // Preferir reglas específicas para el material del drop; si no existe, usar regla del bloque
            var dropRuleOpt = manager.getRule(biome, drop.getType());
            BiomeDropManager.DropRule ruleToApply = dropRuleOpt.orElse(rule);

            double r = random.nextDouble();

            int finalAmount;
            if (r <= ruleToApply.chance) {
                // Se aplica el multiplier
                if (Double.compare(ruleToApply.multiplier, 0.0) == 0) {
                    // multiplier == 0 => drop nulo
                    continue; // no soltar este drop
                }
                finalAmount = Math.max(1, (int) Math.round(baseAmount * ruleToApply.multiplier));
            } else {
                // No aplicar regla: mantener cantidad base
                finalAmount = baseAmount;
            }

            ItemStack out = drop.clone();
            out.setAmount(finalAmount);
            block.getWorld().dropItemNaturally(block.getLocation(), out);
        }
    }
}
