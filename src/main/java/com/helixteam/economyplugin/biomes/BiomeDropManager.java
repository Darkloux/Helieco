package com.helixteam.economyplugin.biomes;

import com.helixteam.economyplugin.EconomyPlugin;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manager responsable de cargar reglas dinámicas de drops por bioma desde config.yml.
 * Estructura esperada en config.yml bajo la clave `biome_drops`:
 * biome_drops:
 *   PLAINS:
 *     WHEAT:
 *       chance: 0.10
 *       multiplier: 2.0
 *
 * Semántica importante (global):
 * - `chance` es la probabilidad (0.0..1.0) de que se aplique `multiplier`.
 *   Si no está presente, por defecto chance = 1.0.
 * - `multiplier` se aplica únicamente cuando la probabilidad acierta.
 *   Si no está presente, por defecto multiplier = 1.0.
 * - Si multiplier == 0 y la probabilidad acierta, el drop resultante debe ser vacío.
 */
public class BiomeDropManager {

    public static class DropRule {
        public final Material material;
        public final double chance; // probabilidad de aplicar el multiplier (0..1)
        public final double multiplier; // multiplicador aplicado si chance acierta

        public DropRule(Material material, double chance, double multiplier) {
            this.material = material;
            this.chance = chance;
            this.multiplier = multiplier;
        }
    }

    private final Map<Biome, Map<Material, DropRule>> rules = new HashMap<>();

    public BiomeDropManager() {
    }

    /**
     * Carga las reglas desde la configuración del plugin.
     * Valida silenciosamente nombres inválidos de Biome o Material (los ignora).
     */
    public void loadConfig(EconomyPlugin plugin) {
        rules.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("biome_drops");
        if (root == null) return;

        for (String biomeKey : root.getKeys(false)) {
            if (biomeKey == null) continue;
            Biome biome;
            try {
                biome = Biome.valueOf(biomeKey.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Biome inválido en config: " + biomeKey + " - se ignora");
                continue;
            }

            ConfigurationSection biomeSection = root.getConfigurationSection(biomeKey);
            if (biomeSection == null) continue;

            Map<Material, DropRule> materialRules = new HashMap<>();

            for (String matKey : biomeSection.getKeys(false)) {
                if (matKey == null) continue;
                Material material;
                try {
                    material = Material.valueOf(matKey.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Material inválido en config (biome " + biomeKey + "): " + matKey + " - se ignora");
                    continue;
                }

                ConfigurationSection ruleSection = biomeSection.getConfigurationSection(matKey);
                // Defaults: chance=1.0, multiplier=1.0
                double chance = 1.0;
                double multiplier = 1.0;

                if (ruleSection != null) {
                    if (ruleSection.contains("chance")) chance = ruleSection.getDouble("chance", 1.0);
                    if (ruleSection.contains("multiplier")) multiplier = ruleSection.getDouble("multiplier", 1.0);
                } else {
                    // Soporta también casos sencillos donde la clave apunta a un número (se interpreta como multiplier)
                    try {
                        multiplier = biomeSection.getDouble(matKey, 1.0);
                    } catch (Exception ignored) {
                    }
                }

                // Sanitizar valores: chance debe estar en [0.0, 1.0]. multiplier no debe ser negativo.
                if (Double.isNaN(chance) || chance < 0.0 || chance > 1.0) {
                    plugin.getLogger().warning("Chance fuera de rango en config (biome " + biomeKey + ", material " + matKey + "): " + chance + " - será recortado a 0..1");
                    chance = Math.max(0.0, Math.min(1.0, Double.isNaN(chance) ? 0.0 : chance));
                }
                if (Double.isNaN(multiplier) || multiplier < 0.0) {
                    plugin.getLogger().warning("Multiplier inválido en config (biome " + biomeKey + ", material " + matKey + "): " + multiplier + " - se ajustará a >=0");
                    multiplier = Math.max(0.0, Double.isNaN(multiplier) ? 1.0 : multiplier);
                }

                DropRule rule = new DropRule(material, chance, multiplier);
                materialRules.put(material, rule);
            }

            if (!materialRules.isEmpty()) rules.put(biome, materialRules);
        }
    }

    public Optional<DropRule> getRule(Biome biome, Material material) {
        Map<Material, DropRule> m = rules.get(biome);
        if (m == null) return Optional.empty();
        return Optional.ofNullable(m.get(material));
    }

    /**
     * Indica si existen reglas configuradas para un bioma dado.
     * Si no existen reglas para el bioma, el plugin no debe interferir
     * con los drops por defecto en ese bioma.
     */
    public boolean hasRulesForBiome(Biome biome) {
        return rules.containsKey(biome);
    }

}
