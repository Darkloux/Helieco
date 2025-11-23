package com.helixteam.economyplugin.currency;

import com.helixteam.economyplugin.EconomyPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
 

public class CurrencyStorage {

    private final EconomyPlugin plugin;
    private final File dataDir;

    public CurrencyStorage(EconomyPlugin plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "data/currency");
        if (!dataDir.exists()) dataDir.mkdirs();
    }

    public void saveLandCurrency(LandCurrency currency) {
        File f = new File(dataDir, currency.getLandId() + ".yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        cfg.set("landId", currency.getLandId());
        cfg.set("name", currency.getName());
        // store bankBalance as string to preserve precision
        cfg.set("bankBalance", currency.getBankBalance() == null ? "0" : currency.getBankBalance().toPlainString());

        // issued count (number of bills in circulation)
        cfg.set("issuedCount", currency.getIssuedCount());

        try {
            cfg.save(f);
            boolean logSave = plugin.getConfig().getBoolean("currency.storage.log_save", false);
            if (logSave) plugin.getLogger().info("Saved currency file: " + f.getAbsolutePath());
            else plugin.getDebugLogger().fine("Saved currency file: " + f.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().severe("No se pudo guardar currency for land " + currency.getLandId() + ": " + e.getMessage());
        }
    }

    public LandCurrency loadLandCurrency(String landId) {
        File f = new File(dataDir, landId + ".yml");
        if (!f.exists()) return null;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        String name = cfg.getString("name", "");
        java.math.BigDecimal bank;
        if (cfg.isString("bankBalance")) {
            try {
                bank = new java.math.BigDecimal(cfg.getString("bankBalance"));
            } catch (Exception e) {
                bank = java.math.BigDecimal.valueOf(cfg.getDouble("bankBalance", 0.0));
            }
        } else {
            bank = java.math.BigDecimal.valueOf(cfg.getDouble("bankBalance", 0.0));
        }

        LandCurrency lc = new LandCurrency(landId, name);
        lc.setBankBalance(bank);

        // load issued count
        if (cfg.contains("issuedCount")) {
            int c = cfg.getInt("issuedCount", 0);
            lc.setIssuedCount(c);
        }

        return lc;
    }

    public List<LandCurrency> loadAll() {
        List<LandCurrency> out = new ArrayList<>();
        File[] files = dataDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            try {
                String base = f.getName().replaceFirst("\\.yml$", "");
                // base may be UUID or ULID or any string id; pass as-is
                LandCurrency lc = loadLandCurrency(base);
                if (lc != null) out.add(lc);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
