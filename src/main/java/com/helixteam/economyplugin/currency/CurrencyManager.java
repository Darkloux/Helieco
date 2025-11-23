package com.helixteam.economyplugin.currency;

import com.helixteam.economyplugin.EconomyPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
 
import java.util.ArrayList;
import java.math.RoundingMode;
import java.lang.reflect.Method;

import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Manager responsable de la persistencia y acceso a las monedas por Land.
 * Usa archivos YAML en `data/currency/<land_uuid>.yml`.
 */
public class CurrencyManager {

    private final EconomyPlugin plugin;
    private final CurrencyStorage storage;
    private final Map<String, LandCurrency> currencies = new HashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> landLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, org.bukkit.scheduler.BukkitTask> pendingRefresh = new java.util.concurrent.ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask periodicSyncTask = null;

    public CurrencyManager(EconomyPlugin plugin) {
        this.plugin = plugin;
        this.storage = new CurrencyStorage(plugin);
    }

    /**
     * Recalcula el valor por billete para la Land indicada y actualiza:
     * - los BillData en memoria (LandCurrency)
     * - los PDC y lore de los ItemStacks en inventarios online y Item entities
     */
    public void refreshBillLore(String landId) {
        LandCurrency lc = getOrCreate(landId);
        int totalCirculating = lc.getIssuedCount();
        java.math.BigDecimal valuePer = java.math.BigDecimal.ZERO;
        if (totalCirculating > 0) {
            try {
                valuePer = lc.getBankBalance().divide(java.math.BigDecimal.valueOf(totalCirculating), 2, RoundingMode.DOWN);
            } catch (Exception e) {
                valuePer = java.math.BigDecimal.ZERO;
            }
        }

        // Persistir cambios en disco
        save(lc);

        // Actualizar inventarios de jugadores online
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            // main inventory
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it == null) continue;
                BillData from = BillData.fromItem(it, plugin);
                if (from == null) continue;
                if (!from.getLandId().equals(landId)) continue;
                // actualizar PDC y meta usando los datos del item pero con nuevo valor
                BillData updated = new BillData("", from.getLandId(), valuePer, from.getIssueDate(), from.getExpireDate(), from.getMaterial());
                updated.writeToItem(it, plugin);
                var meta = it.getItemMeta();
                if (meta != null) {
                    String pretty = valuePer.stripTrailingZeros().toPlainString();
                    // Display name must be only the currency name
                    meta.setDisplayName(lc.getName() == null || lc.getName().isEmpty() ? "Land" : lc.getName());
                    java.util.List<String> lore = new ArrayList<>();
                    lore.add("Emisor: " + (lc.getName() == null || lc.getName().isEmpty() ? "-" : lc.getName()));
                    lore.add("Valor: " + pretty);
                    if (from.getExpireDate() != null && !from.getExpireDate().isEmpty()) lore.add("Vence: " + from.getExpireDate());
                    meta.setLore(lore);
                    it.setItemMeta(meta);
                }
                inv.setItem(i, it);
            }

            // offhand
            try {
                ItemStack off = inv.getItemInOffHand();
                if (off != null) {
                    BillData from = BillData.fromItem(off, plugin);
                    if (from != null && from.getLandId().equals(landId)) {
                        BillData updated = new BillData("", from.getLandId(), valuePer, from.getIssueDate(), from.getExpireDate(), from.getMaterial());
                        updated.writeToItem(off, plugin);
                        var meta = off.getItemMeta();
                        if (meta != null) {
                            String pretty = valuePer.stripTrailingZeros().toPlainString();
                            meta.setDisplayName(lc.getName() == null || lc.getName().isEmpty() ? "Land" : lc.getName());
                            java.util.List<String> lore = new ArrayList<>();
                            lore.add("Emisor: " + (lc.getName() == null || lc.getName().isEmpty() ? "-" : lc.getName()));
                            lore.add("Valor: " + pretty);
                            if (from.getExpireDate() != null && !from.getExpireDate().isEmpty()) lore.add("Vence: " + from.getExpireDate());
                            meta.setLore(lore);
                            off.setItemMeta(meta);
                        }
                        inv.setItemInOffHand(off);
                    }
                }
            } catch (Throwable ignored) {}

            // armor
            try {
                ItemStack[] armor = inv.getArmorContents();
                boolean changed = false;
                for (int a = 0; a < armor.length; a++) {
                    ItemStack arm = armor[a];
                    if (arm == null) continue;
                    BillData from = BillData.fromItem(arm, plugin);
                    if (from == null) continue;
                    if (!from.getLandId().equals(landId)) continue;
                    BillData updated = new BillData("", from.getLandId(), valuePer, from.getIssueDate(), from.getExpireDate(), from.getMaterial());
                    updated.writeToItem(arm, plugin);
                    var meta = arm.getItemMeta();
                    if (meta != null) {
                        String pretty = valuePer.stripTrailingZeros().toPlainString();
                        meta.setDisplayName(lc.getName() == null || lc.getName().isEmpty() ? "Land" : lc.getName());
                        java.util.List<String> lore = new ArrayList<>();
                        lore.add("Emisor: " + (lc.getName() == null || lc.getName().isEmpty() ? "-" : lc.getName()));
                        lore.add("Valor: " + pretty);
                        if (from.getExpireDate() != null && !from.getExpireDate().isEmpty()) lore.add("Vence: " + from.getExpireDate());
                        meta.setLore(lore);
                        arm.setItemMeta(meta);
                    }
                    armor[a] = arm;
                    changed = true;
                }
                if (changed) inv.setArmorContents(armor);
            } catch (Throwable ignored) {}
        }

        // Actualizar entidades Item en los mundos
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!(e instanceof org.bukkit.entity.Item)) continue;
                org.bukkit.entity.Item ie = (org.bukkit.entity.Item) e;
                ItemStack is = ie.getItemStack();
                if (is == null) continue;
                BillData from = BillData.fromItem(is, plugin);
                    if (from == null) continue;
                    if (!from.getLandId().equals(landId)) continue;
                    BillData updated = new BillData("", from.getLandId(), valuePer, from.getIssueDate(), from.getExpireDate(), from.getMaterial());
                    updated.writeToItem(is, plugin);
                    var meta = is.getItemMeta();
                    if (meta != null) {
                        String pretty = valuePer.stripTrailingZeros().toPlainString();
                        meta.setDisplayName(lc.getName() == null || lc.getName().isEmpty() ? "Land" : lc.getName());
                        java.util.List<String> lore = new ArrayList<>();
                        lore.add("Emisor: " + (lc.getName() == null || lc.getName().isEmpty() ? "-" : lc.getName()));
                        lore.add("Valor: " + pretty);
                        if (from.getExpireDate() != null && !from.getExpireDate().isEmpty()) lore.add("Vence: " + from.getExpireDate());
                        meta.setLore(lore);
                        is.setItemMeta(meta);
                    }
                    ie.setItemStack(is);
            }
        }
    }

    public void loadAll() {
        List<LandCurrency> all = storage.loadAll();
        currencies.clear();
        for (LandCurrency lc : all) {
            currencies.put(lc.getLandId(), lc);
        }
        plugin.getLogger().info("Loaded " + currencies.size() + " land currencies from disk.");
    }

    public LandCurrency getOrCreate(String landId) {
        return currencies.computeIfAbsent(landId, id -> {
            LandCurrency lc = new LandCurrency(id, "");
            storage.saveLandCurrency(lc);
            return lc;
        });
    }

    public void save(LandCurrency currency) {
        currencies.put(currency.getLandId(), currency);
        storage.saveLandCurrency(currency);
    }

    /**
     * Ejecuta la operación provista bajo un lock asociado a la landId para evitar
     * race conditions entre emisiones y canjeos.
     */
    public void runLocked(String landId, Runnable op) {
        Object lock = landLocks.computeIfAbsent(landId, k -> new Object());
        synchronized (lock) {
            op.run();
        }
    }

    /**
     * Pide una actualización (debounced) del lore y PDC de billetes para la land.
     * Esto evita ejecutar refreshBillLore múltiples veces seguidas y protege el rendimiento.
     */
    public void requestRefresh(String landId) {
        // Intentar reservar una tarea pendiente de forma atómica para evitar races
        // Creamos la tarea y la insertamos solo si no existía otra
        org.bukkit.scheduler.BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                refreshBillLore(landId);
            } finally {
                pendingRefresh.remove(landId);
            }
        }, 2L);
        // Si ya existe una tarea, cancelar la recién creada y no reemplazarla
        org.bukkit.scheduler.BukkitTask prev = pendingRefresh.putIfAbsent(landId, task);
        if (prev != null) {
            // ya había una tarea pendiente; cancelar la que acabamos de crear
            try { task.cancel(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Intento robusto de obtener el objeto Land desde LandsIntegration usando reflection
     */
    private Object findLandById(String landId) {
        var lands = plugin.getLands();
        if (lands == null) return null;
        Class<?> liCls = lands.getClass();

        // try methods on Lands API with verbose logging
        String[] tryNames = new String[]{"getLand", "getLandById", "findLandById", "getLandByULID", "getById", "get"};
        for (String name : tryNames) {
            try {
                Method m = liCls.getMethod(name, String.class);
                plugin.getLogger().fine("Trying Lands method: " + name + "(String)");
                try {
                    Object res = m.invoke(lands, landId);
                    plugin.getLogger().fine("Result for " + name + ": " + (res == null ? "null" : res.toString()));
                    if (res != null) return res;
                } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                    plugin.getLogger().warning("Error invoking " + name + " on LandsIntegration: " + e.getMessage());
                }
            } catch (NoSuchMethodException e) {
                plugin.getLogger().fine("Lands method not present: " + name);
            }
        }

        // Intentar con UUID parameter
        try {
            java.util.UUID uid = java.util.UUID.fromString(landId);
            for (Method m : liCls.getMethods()) {
                if (!m.getName().toLowerCase().contains("land")) continue;
                var params = m.getParameterTypes();
                if (params.length == 1 && params[0].equals(java.util.UUID.class)) {
                    try {
                        Object res = m.invoke(lands, uid);
                        if (res != null) return res;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (IllegalArgumentException ignored) {}

        // Fallback: inspeccionar métodos que acepten String y devolver algo útil
        for (Method m : liCls.getMethods()) {
            var params = m.getParameterTypes();
            if (params.length == 1 && params[0].equals(String.class) && m.getName().toLowerCase().contains("land")) {
                try {
                    Object res = m.invoke(lands, landId);
                    if (res != null) return res;
                } catch (Throwable ignored) {}
            }
        }

        // Nueva estrategia: intentar obtener colecciones de lands y buscar coincidencias por ULID
        plugin.getLogger().fine("findLandById: intentando búsqueda por colección/iterables en LandsIntegration");
        for (Method m : liCls.getMethods()) {
            try {
                // Solo métodos sin parámetros
                if (m.getParameterCount() != 0) continue;
                Class<?> ret = m.getReturnType();
                boolean looksLikeCollection = java.util.Collection.class.isAssignableFrom(ret) || ret.isArray() || java.lang.Iterable.class.isAssignableFrom(ret) || java.util.Map.class.isAssignableFrom(ret);
                if (!looksLikeCollection) continue;
                plugin.getLogger().fine("Invoking candidate method for collections: " + m.getName());
                Object out = null;
                try { out = m.invoke(lands); } catch (Throwable t) { plugin.getLogger().fine("Invocation failed for " + m.getName() + ": " + t.getMessage()); continue; }
                if (out == null) continue;

                java.util.Iterator<?> iter = null;
                if (out.getClass().isArray()) {
                    // manejar arrays de cualquier tipo de forma segura
                    int len = java.lang.reflect.Array.getLength(out);
                    for (int ai = 0; ai < len; ai++) {
                        Object candidate = java.lang.reflect.Array.get(out, ai);
                        if (candidate == null) continue;
                        // Comprobar candidato
                        try {
                            String ts = candidate.toString();
                            if (ts != null && ts.contains(landId)) {
                                plugin.getLogger().fine("findLandById: matched land via collection method " + m.getName());
                                return candidate;
                            }
                        } catch (Throwable ignored) {}
                        try {
                            for (String gid : new String[]{"getUlid", "getULID", "getId", "getLandId", "ulid"}) {
                                try {
                                    Method gm = candidate.getClass().getMethod(gid);
                                    Object val = gm.invoke(candidate);
                                    if (val != null && val.toString().equals(landId)) {
                                        plugin.getLogger().fine("findLandById: matched land via getter " + gid + " on " + m.getName());
                                        return candidate;
                                    }
                                } catch (NoSuchMethodException ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                    continue;
                } else if (out instanceof java.util.Map) {
                    iter = ((java.util.Map<?,?>) out).values().iterator();
                } else if (out instanceof java.util.Collection) {
                    iter = ((java.util.Collection<?>) out).iterator();
                } else if (out instanceof java.lang.Iterable) {
                    iter = ((java.lang.Iterable<?>) out).iterator();
                }

                if (iter == null) continue;
                while (iter.hasNext()) {
                    Object candidate = iter.next();
                    if (candidate == null) continue;
                    // Comprobar toString
                    try {
                        String ts = candidate.toString();
                        if (ts != null && ts.contains(landId)) {
                            plugin.getLogger().fine("findLandById: matched land via collection method " + m.getName());
                            return candidate;
                        }
                    } catch (Throwable ignored) {}
                    // Comprobar getters comunes en el objeto land
                    try {
                        for (String gid : new String[]{"getUlid", "getULID", "getId", "getLandId", "ulid"}) {
                            try {
                                Method gm = candidate.getClass().getMethod(gid);
                                Object val = gm.invoke(candidate);
                                if (val != null && val.toString().equals(landId)) {
                                    plugin.getLogger().fine("findLandById: matched land via getter " + gid + " on " + m.getName());
                                    return candidate;
                                }
                            } catch (NoSuchMethodException ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                // defensa: no dejar que una excepción rompa la búsqueda
                plugin.getLogger().fine("Error scanning method " + m.getName() + ": " + t.getMessage());
            }
        }
        return null;
    }

    private java.math.BigDecimal readBankFromLandObject(Object land) {
        if (land == null) return null;
        Class<?> cls = land.getClass();
        String[] names = new String[]{"getBank", "getBalance", "getBankBalance", "getMoney", "getBankAmount", "getBalanceAmount", "getVaultBalance", "getDeposit"};
        for (String n : names) {
            try {
                Method m = cls.getMethod(n);
                Object out = m.invoke(land);
                if (out == null) continue;
                if (out instanceof java.math.BigDecimal) return (java.math.BigDecimal) out;
                if (out instanceof Number) return java.math.BigDecimal.valueOf(((Number) out).doubleValue());
                String s = out.toString();
                try { return new java.math.BigDecimal(s); } catch (Exception ignored) {}
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                plugin.getLogger().warning("Error reading bank from Land object: " + t.getMessage());
            }
        }

        // Fallback: inspeccionar cualquier método sin parámetros que parezca devolver balance
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String nm = m.getName().toLowerCase();
            if (!(nm.contains("bank") || nm.contains("balance") || nm.contains("money") || nm.contains("deposit"))) continue;
            try {
                Object out = m.invoke(land);
                if (out == null) continue;
                if (out instanceof java.math.BigDecimal) return (java.math.BigDecimal) out;
                if (out instanceof Number) return java.math.BigDecimal.valueOf(((Number) out).doubleValue());
                try { return new java.math.BigDecimal(out.toString()); } catch (Exception ignored) {}
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean writeBankToLandObject(Object land, java.math.BigDecimal amount) {
        if (land == null) return false;
        Class<?> cls = land.getClass();
        String[] tryNames = new String[]{"setBankBalance", "setBalance", "setBank", "setDeposit", "updateBalance", "setBankAmount"};
        for (String n : tryNames) {
            for (Method m : cls.getMethods()) {
                if (!m.getName().equalsIgnoreCase(n)) continue;
                var params = m.getParameterTypes();
                if (params.length != 1) continue;
                try {
                    if (params[0].equals(java.math.BigDecimal.class)) { m.invoke(land, amount); return true; }
                    if (params[0].equals(double.class) || params[0].equals(Double.class)) { m.invoke(land, amount.doubleValue()); return true; }
                    if (params[0].equals(long.class) || params[0].equals(Long.class)) { m.invoke(land, amount.longValue()); return true; }
                    if (params[0].equals(String.class)) { m.invoke(land, amount.toPlainString()); return true; }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Error writing bank to Land object via " + m.getName() + ": " + t.getMessage());
                }
            }
        }

        // Fallback: buscar cualquier método que acepte un parámetro numérico y cuyo nombre contenga bank/balance
        for (Method m : cls.getMethods()) {
            String nm = m.getName().toLowerCase();
            if (!(nm.contains("bank") || nm.contains("balance") || nm.contains("deposit"))) continue;
            var params = m.getParameterTypes();
            if (params.length != 1) continue;
            try {
                Class<?> p = params[0];
                if (p.equals(double.class) || p.equals(Double.class)) { m.invoke(land, amount.doubleValue()); return true; }
                if (p.equals(long.class) || p.equals(Long.class)) { m.invoke(land, amount.longValue()); return true; }
                if (p.equals(String.class)) { m.invoke(land, amount.toPlainString()); return true; }
            } catch (Throwable ignored) {}
        }

        // Si no encontramos cómo escribir, intentar obtener un objeto bank y buscar setBalance
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String nm = m.getName().toLowerCase();
            if (!(nm.contains("bank") || nm.contains("account"))) continue;
            try {
                Object bankObj = m.invoke(land);
                if (bankObj == null) continue;
                Class<?> bcls = bankObj.getClass();
                for (Method bm : bcls.getMethods()) {
                    String bmn = bm.getName().toLowerCase();
                    if (!(bmn.contains("set") && (bmn.contains("balance") || bmn.contains("amount")))) continue;
                    var params = bm.getParameterTypes();
                    if (params.length != 1) continue;
                    try {
                        if (params[0].equals(java.math.BigDecimal.class)) { bm.invoke(bankObj, amount); return true; }
                        if (params[0].equals(double.class) || params[0].equals(Double.class)) { bm.invoke(bankObj, amount.doubleValue()); return true; }
                        if (params[0].equals(String.class)) { bm.invoke(bankObj, amount.toPlainString()); return true; }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    /**
     * Intenta leer el balance desde Lands y escribirlo en la LandCurrency local.
     */
    public boolean syncFromLands(String landId) {
        try {
            Object land = findLandById(landId);
            if (land == null) {
                // do not spam logs if land not found during periodic runs
                plugin.getLogger().finer("syncFromLands: no se encontró Land para id=" + landId);
                return false;
            }
            java.math.BigDecimal bank = readBankFromLandObject(land);
            if (bank == null) {
                plugin.getLogger().finer("syncFromLands: no se pudo leer banco desde Land " + landId);
                return false;
            }
            var lc = getOrCreate(landId);
            lc.setBankBalance(bank);
            save(lc);
            requestRefresh(landId);
            // Log successful sync only if configured to do so (reduces log noise)
            if (plugin.getConfig().getBoolean("currency.sync.log_success", false)) {
                plugin.getLogger().info("Sincronizado banco desde Lands para " + landId + ": " + bank);
            } else {
                plugin.getLogger().finer("Sincronizado banco desde Lands para " + landId);
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Error en syncFromLands(" + landId + "): " + t.getMessage());
            return false;
        }
    }

    /**
     * Intenta escribir el balance local hacia la Land en Lands.
     */
    public boolean syncToLands(String landId, java.math.BigDecimal amount) {
        try {
            Object land = findLandById(landId);
            if (land == null) {
                plugin.getLogger().fine("syncToLands: no se encontró Land para id=" + landId);
                return false;
            }
            boolean ok = writeBankToLandObject(land, amount == null ? java.math.BigDecimal.ZERO : amount);
            if (ok) plugin.getLogger().fine("Escrito banco hacia Lands para " + landId + ": " + amount);
            else plugin.getLogger().warning("No se encontró método para escribir banco en Land " + landId);
            return ok;
        } catch (Throwable t) {
            plugin.getLogger().warning("Error en syncToLands(" + landId + "): " + t.getMessage());
            return false;
        }
    }

    public void startPeriodicSync() {
        // cancelar si ya hay tarea
        if (periodicSyncTask != null) {
            try { periodicSyncTask.cancel(); } catch (Throwable ignored) {}
            periodicSyncTask = null;
        }
        boolean enabled = plugin.getConfig().getBoolean("currency.sync.enabled", false);
        int secs = plugin.getConfig().getInt("currency.sync.interval_seconds", 300);
        if (!enabled || secs <= 0) return;
        long ticks = secs * 20L;
        periodicSyncTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                // Run sync for each land, but avoid noisy per-land logs. Collect summary counts.
                int total = 0;
                int succeeded = 0;
                int failed = 0;
                for (String id : new ArrayList<>(currencies.keySet())) {
                    total++;
                    try {
                        boolean ok = syncFromLands(id);
                        if (ok) succeeded++; else failed++;
                    } catch (Throwable ignored) {
                        failed++;
                    }
                }
                // Only log summary if there were any failures or if configured to log successes
                boolean logSuccess = plugin.getConfig().getBoolean("currency.sync.log_success", false);
                if (failed > 0 || logSuccess) {
                    plugin.getLogger().info("Periodic sync summary: total=" + total + " succeeded=" + succeeded + " failed=" + failed);
                } else {
                    plugin.getLogger().finer("Periodic sync completed: total=" + total + " succeeded=" + succeeded);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Error en periodic sync: " + t.getMessage());
            }
        }, 20L, ticks);
        plugin.getLogger().fine("Periodic sync scheduled every " + secs + " seconds.");
    }

    public boolean hasCurrency(String landId) {
        return currencies.containsKey(landId);
    }

}
