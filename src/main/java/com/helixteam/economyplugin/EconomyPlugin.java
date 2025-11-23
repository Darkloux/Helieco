package com.helixteam.economyplugin;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.helixteam.economyplugin.biomes.BiomeDropManager;
import com.helixteam.economyplugin.biomes.BiomeDropListener;
import com.helixteam.economyplugin.currency.CurrencyCommands;
import net.milkbowl.vault.economy.Economy;
import me.angeschossen.lands.api.LandsIntegration;

public class EconomyPlugin extends JavaPlugin {

    private static EconomyPlugin instance;
    private LandsIntegration lands; // typed reference to LandsIntegration when available
    private Economy essentialsEco;
    private BiomeDropManager biomeDropManager;
    private com.helixteam.economyplugin.currency.CurrencyManager currencyManager;
    private com.helixteam.economyplugin.work.WorkPointManager workManager;
    // Optional debug file handler and logger: writes FINE/FINER logs to a file when enabled
    private FileHandler debugFileHandler = null;
    private Logger debugLogger = null;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Setup debug file logging if enabled in config
        try {
            boolean dbg = getConfig().getBoolean("logging.debug_to_file.enabled", false);
            if (dbg) {
                String fname = getConfig().getString("logging.debug_to_file.filename", "debug.log");
                // Ensure plugin data folder exists
                if (!getDataFolder().exists()) getDataFolder().mkdirs();
                String path = new java.io.File(getDataFolder(), fname).getAbsolutePath();
                debugFileHandler = new FileHandler(path, true);
                debugFileHandler.setFormatter(new SimpleFormatter());
                debugFileHandler.setLevel(Level.FINE);
                // Create separate logger for debug traces to avoid console spam
                debugLogger = Logger.getLogger(getName() + ".debug");
                debugLogger.setLevel(Level.FINE);
                // Prevent parent handlers (console) from receiving these debug messages
                debugLogger.setUseParentHandlers(false);
                debugLogger.addHandler(debugFileHandler);
                debugLogger.fine("Debug file logging enabled: " + path);
            }
        } catch (Throwable t) {
            getLogger().warning("Could not enable debug file logging: " + t.getMessage());
        }

        // Inicializar Lands (tipado). Requiere que la API esté disponible en tiempo de ejecución.
        try {
            lands = LandsIntegration.of(this);
            if (lands == null) {
                getLogger().warning("Lands API encontrada pero 'of' devolvió null.");
            } else {
                getLogger().info("LandsAPI cargada correctamente (tipada).");
            }
        } catch (LinkageError e) {
            getLogger().warning("LandsAPI no encontrada en classpath. Continuando sin integración.");
            lands = null;
        } catch (Throwable t) {
            getLogger().severe("Error inicializando LandsAPI: " + t.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Inicializar economía (Vault provider)
        if (!setupEconomy()) {
            getLogger().severe("No se pudo cargar EssentialsX Economy (Vault provider). Deshabilitando plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Economía cargada correctamente.");
        // Inicializar BiomeDropManager y listeners
        biomeDropManager = new BiomeDropManager();
        biomeDropManager.loadConfig(this);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new BiomeDropListener(biomeDropManager, this), this);
        getLogger().info("BiomeDropListener registrado y configuración cargada.");

        // Inicializar WorkPointManager y listener
        workManager = new com.helixteam.economyplugin.work.WorkPointManager(this);
        workManager.loadConfig();
        pm.registerEvents(new com.helixteam.economyplugin.work.WorkListener(workManager, this), this);
        getLogger().info("WorkListener registrado y configuración de trabajo cargada.");

        // Inicializar CurrencyManager y cargar persistencia
        currencyManager = new com.helixteam.economyplugin.currency.CurrencyManager(this);
        currencyManager.loadAll();
        // Start periodic sync with Lands if configured
        currencyManager.startPeriodicSync();
        getLogger().info("CurrencyManager inicializado.");
        // Registrar comando de gestión de moneda
        if (getCommand("landcurrency") != null) {
            getCommand("landcurrency").setExecutor(new CurrencyCommands(this));
            // Register tab completer
            getCommand("landcurrency").setTabCompleter(new com.helixteam.economyplugin.currency.CurrencyTabCompleter(this));
            getLogger().info("Comando /landcurrency registrado.");
        } else {
            getLogger().warning("Comando 'landcurrency' no está definido en plugin.yml.");
        }
    }

    private boolean setupEconomy() {
        var rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        essentialsEco = rsp.getProvider();
        return essentialsEco != null;
    }

    @Override
    public void onDisable() {
        // Cleanup si es necesario
        if (debugFileHandler != null) {
            try {
                if (debugLogger != null) debugLogger.removeHandler(debugFileHandler);
                debugFileHandler.close();
            } catch (Throwable ignored) {}
            debugFileHandler = null;
            debugLogger = null;
        }
    }

    public Logger getDebugLogger() {
        // Fallback to plugin logger if debug logger is not configured
        return (debugLogger != null) ? debugLogger : getLogger();
    }

    public static EconomyPlugin getInstance() {
        return instance;
    }

    public LandsIntegration getLands() {
        return lands;
    }

    public Economy getEconomy() {
        return essentialsEco;
    }

    public com.helixteam.economyplugin.currency.CurrencyManager getCurrencyManager() {
        return currencyManager;
    }

    public BiomeDropManager getBiomeDropManager() {
        return biomeDropManager;
    }

    public com.helixteam.economyplugin.work.WorkPointManager getWorkPointManager() {
        return workManager;
    }

    public NamespacedKey key(String key) {
        return new NamespacedKey(this, key);
    }
}
