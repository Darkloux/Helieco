package com.helixteam.economyplugin.biomes;

import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener que aplica las reglas dinámicas cargadas por BiomeDropManager.
 * Actualmente implementa BlockBreakEvent como ejemplo; puede ampliarse.
 */
public class BiomeDropListener implements Listener {

    private final BiomeDropManager manager;
    private final Random random = new Random();
    // cooldowns por jugador -> material -> timestamp(ms)
    private final Map<UUID, Map<org.bukkit.Material, Long>> lastApplied = new ConcurrentHashMap<>();
    private final int cooldownSeconds;

    public BiomeDropListener(BiomeDropManager manager, org.bukkit.plugin.Plugin plugin) {
        this.manager = manager;
        this.cooldownSeconds = plugin.getConfig().getInt("biome_drop_cooldown_seconds", 5);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        var block = event.getBlock();
        var player = event.getPlayer();

        var biome = block.getBiome();
        var material = block.getType();

        // Si no hay reglas configuradas para este bioma, no interferimos
        if (!manager.hasRulesForBiome(biome)) return;

        var opt = manager.getRule(biome, material);
        BiomeDropManager.DropRule rule = null;

        // Obtenemos drops posibles una sola vez (se usará tanto para decidir si aplicar reglas
        // como para generar las entidades resultantes).
        Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand());

        // Comprueba cooldowns por material: recopilamos qué materiales están en cooldown
        // para este jugador. En vez de devolver temprano, si hay cooldown para un material
        // concreto simplemente NO aplicaremos la regla (se reemitirá el drop base).
        UUID puid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<org.bukkit.Material, Long> playerMap = lastApplied.get(puid);
        java.util.Set<org.bukkit.Material> cooled = new java.util.HashSet<>();
        if (playerMap != null) {
            for (ItemStack d : drops) {
                Long t = playerMap.get(d.getType());
                if (t != null && (now - t) < (long) cooldownSeconds * 1000L) {
                    cooled.add(d.getType());
                }
            }
        }

        // Si no hay regla para el bloque en sí, comprobamos si alguna de las posibles
        // piezas a soltar tiene una regla específica. Si ninguna tiene regla, no interferimos.
        if (opt.isEmpty()) {
            boolean anyDropRule = false;
            for (ItemStack d : drops) {
                if (manager.getRule(biome, d.getType()).isPresent()) {
                    anyDropRule = true;
                    break;
                }
            }
            if (!anyDropRule) return;
        } else {
            rule = opt.get();
        }

        // Desactivamos los drops automáticos y gestionamos manualmente según las reglas encontradas
        event.setDropItems(false);

        for (ItemStack drop : drops) {
            int baseAmount = drop.getAmount();

            // Si este material está en cooldown para el jugador, NO aplicamos la regla
            // (se reemitirá el drop base). En caso contrario, buscamos una regla específica
            // para el drop y usamos la regla del bloque como fallback.
            if (cooled.contains(drop.getType())) {
                ItemStack outDefault = drop.clone();
                outDefault.setAmount(baseAmount);
                block.getWorld().dropItemNaturally(block.getLocation(), outDefault);
                continue;
            }

            var dropRuleOpt = manager.getRule(biome, drop.getType());
            BiomeDropManager.DropRule ruleToApply = dropRuleOpt.orElse(rule);

            // Si no existe ninguna regla para este drop concreto (y rule también puede ser null),
            // reemitimos el drop base.
            if (ruleToApply == null) {
                ItemStack outDefault = drop.clone();
                outDefault.setAmount(baseAmount);
                block.getWorld().dropItemNaturally(block.getLocation(), outDefault);
                continue;
            }

            double r = random.nextDouble();

            int finalAmount;
            // Use strict comparison so chance==0.0 never applies and chance==1.0 always applies
            if (r < ruleToApply.chance) {
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

        // Registrar timestamps de aplicación para los materiales que realmente procesamos
        UUID playerId = player.getUniqueId();
        Map<org.bukkit.Material, Long> map = lastApplied.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        long appliedAt = System.currentTimeMillis();
        for (ItemStack d : drops) {
            org.bukkit.Material m = d.getType();
            if (!cooled.contains(m) && manager.getRule(biome, m).isPresent() || (rule != null && !cooled.contains(m))) {
                map.put(m, appliedAt);
            }
        }
    }
}
