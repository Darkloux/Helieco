package com.helixteam.economyplugin.currency;

import com.helixteam.economyplugin.EconomyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import org.bukkit.inventory.ItemStack;
import me.angeschossen.lands.api.LandsIntegration;
import java.lang.reflect.Method;

import net.milkbowl.vault.economy.EconomyResponse;

/**
 * Comandos para /landcurrency create|emit|info|redeem
 * Nota: la verificación de owner con LandsAPI debe ajustarse a la API real de Lands.
 */
public class CurrencyCommands implements CommandExecutor {

    private final EconomyPlugin plugin;

    public CurrencyCommands(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Solo jugadores pueden usar este comando.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            // show organized help by default
            return showHelp(player, 1);
        }

        String sub = args[0].toLowerCase();
        var cm = plugin.getCurrencyManager();

        try {
            switch (sub) {
                case "reload": {
                    return handleReload(sender);
                }
                case "create": {
                    if (args.length < 2) {
                        player.sendMessage("Uso: /landcurrency create <name>");
                        return true;
                    }
                    String name = args[1];
                    // Obtener land id (string) - puede ser UUID, ULID, etc.
                    String landId = tryGetLandOfPlayer(player);
                    if (landId == null) {
                        player.sendMessage("No se pudo determinar la Land del jugador o no eres owner.");
                        return true;
                    }

                    var lc = cm.getOrCreate(landId);
                    lc.setName(name);
                    cm.save(lc);
                    plugin.getLogger().info("Created/updated LandCurrency for land " + landId + " (name=" + name + ")");
                    player.sendMessage("Moneda creada/actualizada para la Land: " + name);
                    return true;
                }
                case "emit": {
                    if (args.length < 2) {
                        player.sendMessage("Uso: /landcurrency emit <cantidad>");
                        return true;
                    }
                    int count;
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException nfe) {
                        player.sendMessage("Cantidad inválida.");
                        return true;
                    }
                    if (count <= 0) {
                        player.sendMessage("Cantidad inválida.");
                        return true;
                    }

                    String landId = tryGetLandOfPlayer(player);
                    if (landId == null) {
                        player.sendMessage("No se pudo determinar la Land del jugador o no eres owner.");
                        return true;
                    }

                    // Antes de emitir, intentar sincronizar desde Lands
                    cm.syncFromLands(landId);

                    var lc = cm.getOrCreate(landId);
                    java.math.BigDecimal bank = lc.getBankBalance();
                    if (bank.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                        player.sendMessage("No hay fondos en el banco de la Land para emitir billetes.");
                        return true;
                    }

                    int maxIssue = plugin.getConfig().getInt("currency.max_issue_count", 100);
                    if (count > maxIssue) {
                        player.sendMessage("No puedes emitir más de " + maxIssue + " billetes en una sola emisión.");
                        return true;
                    }

                    // Ejecutar emisión bajo lock por land para evitar condiciones de carrera
                    cm.runLocked(landId, () -> {
                        // Reobtener lc y bank dentro del lock para consistencia
                        var innerLc = cm.getOrCreate(landId);
                        java.math.BigDecimal innerBank = innerLc.getBankBalance();
                        int currentIssued2 = innerLc.getIssuedCount();
                        int totalCirculating2 = currentIssued2 + count;
                        java.math.BigDecimal valuePer2 = java.math.BigDecimal.ZERO;
                        if (totalCirculating2 > 0) {
                            valuePer2 = innerBank.divide(java.math.BigDecimal.valueOf(totalCirculating2), 2, java.math.RoundingMode.DOWN);
                        }

                        var factory = new BillItemFactory();
                        boolean droppedAny = false;

                        // Create stackable ItemStacks in chunks according to max stack size
                        int remaining = count;
                        ItemStack template = factory.createBill(innerLc, valuePer2, LocalDate.now().plusDays(plugin.getConfig().getInt("currency.expiration_days",30)));
                        int maxStack = template.getMaxStackSize();
                        while (remaining > 0) {
                            int take = Math.min(remaining, maxStack);
                            ItemStack stack = template.clone();
                            stack.setAmount(take);
                            var leftovers = player.getInventory().addItem(stack);
                            if (leftovers != null && !leftovers.isEmpty()) {
                                for (var li : leftovers.values()) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), li);
                                    droppedAny = true;
                                }
                            }
                            remaining -= take;
                        }
                        if (droppedAny) {
                            player.sendMessage("Inventario lleno: algunos billetes fueron soltados en el suelo.");
                        }

                        // Registrar la emisión en el contador de la Land
                        innerLc.addIssued(count);
                        cm.save(innerLc);
                        // Solicitar refresh debounced para propagar lore/PDC actualizado
                        cm.requestRefresh(landId);
                        player.sendMessage("Emitidos " + count + " billetes (valor por billete: " + valuePer2 + ")");
                    });
                    return true;
                }
                case "info": {
                    String landId = tryGetLandOfPlayer(player);
                    if (landId == null) {
                        player.sendMessage("No se pudo determinar la Land del jugador.");
                        return true;
                    }
                    // sincronizar antes de mostrar info
                    cm.syncFromLands(landId);
                    var lc = cm.getOrCreate(landId);
                    player.sendMessage("Moneda: " + lc.getName());
                    player.sendMessage("Banco: " + lc.getBankBalance());
                    player.sendMessage("Billetes en circulación: " + lc.getIssuedCount());
                    return true;
                }
                case "rename": {
                    // Two modes:
                    // - /landcurrency rename <nuevo_nombre>              -> owner renames their own land
                    // - /landcurrency rename <landId> <nuevo_nombre>    -> admin/OP can rename any land by id
                    if (args.length < 2) {
                        player.sendMessage("Uso: /landcurrency rename <nuevo_nombre>  OR  /landcurrency rename <landId> <nuevo_nombre> (admin)");
                        return true;
                    }

                    String landId = null;
                    String newName = null;

                    if (args.length == 2) {
                        // owner mode
                        newName = args[1];
                        landId = tryGetLandOfPlayer(player);
                        if (landId == null) {
                            player.sendMessage("No se pudo determinar la Land del jugador o no eres owner.");
                            return true;
                        }
                    } else {
                        // admin mode: /landcurrency rename <landId> <nuevo_nombre>
                        if (!player.isOp() && !player.hasPermission("helieco.admin")) {
                            player.sendMessage("No tienes permiso para renombrar otras lands (se requiere OP o permiso helieco.admin).");
                            return true;
                        }
                        landId = args[1];
                        newName = args[2];
                    }

                    var lc = cm.getOrCreate(landId);
                    String old = lc.getName();
                    lc.setName(newName);
                    cm.save(lc);
                    cm.requestRefresh(landId);
                    player.sendMessage("Moneda renombrada a: " + newName + (old == null || old.isEmpty() ? "" : " (antes: " + old + ")"));
                    plugin.getLogger().info("Renamed LandCurrency for land " + landId + " -> " + newName + " (was: " + old + ")");
                    return true;
                }
                case "redeem": {
                    // Redeem the bill in player's main hand
                    var item = player.getInventory().getItemInMainHand();
                    var bd = BillData.fromItem(item, plugin);
                    if (bd == null) {
                        player.sendMessage("No hay un billete válido en la mano.");
                        return true;
                    }
                    String landId = bd.getLandId();

                    // Check expiry: only allow redeem if the bill has an expiration date AND it is already expired
                    if (bd.getExpireDate() == null || bd.getExpireDate().isEmpty()) {
                        player.sendMessage("Este billete no tiene fecha de vencimiento y no puede canjearse hasta que expire.");
                        return true;
                    }
                    LocalDate exp = LocalDate.parse(bd.getExpireDate());
                    // If expiration is today or in the future, it's NOT yet expired -> disallow
                    if (!exp.isBefore(LocalDate.now())) {
                        player.sendMessage("El billete aún no ha vencido. Solo puede canjearse después de la fecha de vencimiento: " + bd.getExpireDate());
                        return true;
                    }

                    // Antes de canjear, sincronizar desde Lands (traer balance actual)
                    cm.syncFromLands(landId);

                    // Ejecutar canje bajo lock para evitar races y asegurar atomicidad local
                    cm.runLocked(landId, () -> {
                        var innerLc = cm.getOrCreate(landId);
                        int totalBefore2 = innerLc.getIssuedCount();
                        if (totalBefore2 <= 0) {
                            player.sendMessage("Error: no hay billetes registrados para esta Land.");
                            return;
                        }
                        java.math.BigDecimal value2 = java.math.BigDecimal.ZERO;
                        try {
                            value2 = innerLc.getBankBalance().divide(java.math.BigDecimal.valueOf(totalBefore2), 2, java.math.RoundingMode.DOWN);
                        } catch (ArithmeticException ex) {
                            value2 = java.math.BigDecimal.ZERO;
                        }

                        // Restar del banco (reserva efectiva al canjear)
                        innerLc.setBankBalance(innerLc.getBankBalance().subtract(value2));
                        // Remover bill del registro
                        innerLc.removeOneIssued();
                        cm.save(innerLc);

                        // Intentar pagar al jugador y comprobar resultado
                        try {
                            EconomyResponse resp = plugin.getEconomy().depositPlayer(player, value2.doubleValue());
                            if (resp == null || !resp.transactionSuccess()) {
                                // Revertir cambios en caso de fallo al pagar
                                innerLc.setBankBalance(innerLc.getBankBalance().add(value2));
                                innerLc.addIssued(1);
                                cm.save(innerLc);
                                player.sendMessage("Error al pagar. Operación revertida.");
                                return;
                            }
                        } catch (Throwable ex) {
                            innerLc.setBankBalance(innerLc.getBankBalance().add(value2));
                            innerLc.addIssued(1);
                            cm.save(innerLc);
                            player.sendMessage("Error al pagar (exception). Operación revertida.");
                            plugin.getLogger().severe("Error pagando billete: " + ex.getMessage());
                            return;
                        }

                        // Consumir item en inventario del jugador
                        var inv = player.getInventory();
                        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
                        else inv.setItemInMainHand(null);

                        // Después de un canje exitoso, sincronizar el balance local hacia Lands
                        cm.requestRefresh(landId);
                        cm.syncToLands(landId, innerLc.getBankBalance());
                        player.sendMessage("Canjeado billete por " + value2);
                    });
                    return true;
                }
                case "forceredeem": {
                    // Solo operadores pueden forzar el canje
                    if (!player.isOp() && !player.hasPermission("helieco.forceredeem")) {
                        player.sendMessage("No tienes permiso para forzar el canje (se requiere OP o permiso helieco.forceredeem).");
                        return true;
                    }

                    // Redeem the bill in player's main hand (force, ignoring expiry)
                    var itemF = player.getInventory().getItemInMainHand();
                    var bdF = BillData.fromItem(itemF, plugin);
                    if (bdF == null) {
                        player.sendMessage("No hay un billete válido en la mano para forzar canje.");
                        return true;
                    }
                    String landIdF = bdF.getLandId();

                    // sincronizar antes de canjear
                    cm.syncFromLands(landIdF);

                    cm.runLocked(landIdF, () -> {
                        var innerLc = cm.getOrCreate(landIdF);
                        int totalBefore2 = innerLc.getIssuedCount();
                        if (totalBefore2 <= 0) {
                            player.sendMessage("Error: no hay billetes registrados para esta Land.");
                            return;
                        }
                        java.math.BigDecimal value2 = java.math.BigDecimal.ZERO;
                        try {
                            value2 = innerLc.getBankBalance().divide(java.math.BigDecimal.valueOf(totalBefore2), 2, java.math.RoundingMode.DOWN);
                        } catch (ArithmeticException ex) {
                            value2 = java.math.BigDecimal.ZERO;
                        }

                        // Restar del banco (reserva efectiva al canjear)
                        innerLc.setBankBalance(innerLc.getBankBalance().subtract(value2));
                        innerLc.removeOneIssued();
                        cm.save(innerLc);

                        // Intentar pagar al jugador y comprobar resultado
                        try {
                            EconomyResponse resp = plugin.getEconomy().depositPlayer(player, value2.doubleValue());
                            if (resp == null || !resp.transactionSuccess()) {
                                // Revertir cambios en caso de fallo al pagar
                                innerLc.setBankBalance(innerLc.getBankBalance().add(value2));
                                innerLc.addIssued(1);
                                cm.save(innerLc);
                                player.sendMessage("Error al pagar. Operación revertida.");
                                return;
                            }
                        } catch (Throwable ex) {
                            innerLc.setBankBalance(innerLc.getBankBalance().add(value2));
                            innerLc.addIssued(1);
                            cm.save(innerLc);
                            player.sendMessage("Error al pagar (exception). Operación revertida.");
                            plugin.getLogger().severe("Error pagando billete (forceredeem): " + ex.getMessage());
                            return;
                        }

                        // Consumir item
                        var inv = player.getInventory();
                        if (itemF.getAmount() > 1) itemF.setAmount(itemF.getAmount() - 1);
                        else inv.setItemInMainHand(null);

                        cm.requestRefresh(landIdF);
                        cm.syncToLands(landIdF, innerLc.getBankBalance());
                        player.sendMessage("(FORZADO) Canjeado billete por " + value2);
                    });
                    return true;
                }
                case "sync": {
                    String landId = tryGetLandOfPlayer(player);
                    if (landId == null) {
                        player.sendMessage("No se pudo determinar la Land del jugador o no eres owner.");
                        return true;
                    }
                    boolean ok = cm.syncFromLands(landId);
                    if (ok) player.sendMessage("Sincronización desde Lands completada para " + landId);
                    else player.sendMessage("No se pudo sincronizar desde Lands para " + landId + ". Revisa logs.");
                    return true;
                }
                case "help": {
                    int page = 1;
                    if (args.length >= 2) {
                        try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                    }
                    return showHelp(player, page);
                }
                default:
                    player.sendMessage("Subcomando desconocido: " + sub);
                    return true;
            }
        } catch (Exception e) {
            player.sendMessage("Error ejecutando comando: " + e.getMessage());
            plugin.getLogger().severe("Error en CurrencyCommands: " + e.getMessage());
            return true;
        }
    }

    private boolean showHelp(Player player, int page) {
        // Simple paged help (only one page for now)
        player.sendMessage("--- /landcurrency ayuda (página " + page + ") ---");
        player.sendMessage("/landcurrency create <nombre>  - Crear o actualizar la moneda de la Land");
        player.sendMessage("/landcurrency emit <cantidad> - Emitir billetes (se sincroniza antes con Lands)");
        player.sendMessage("/landcurrency info             - Mostrar info y sincronizar desde Lands");
        player.sendMessage("/landcurrency redeem           - Canjear billete (solo si está vencido)");
        player.sendMessage("/landcurrency rename <nombre>  - Renombrar la moneda de la Land");
        player.sendMessage("/landcurrency forceredeem      - Forzar canje (OP/permiso requerido)");
        player.sendMessage("/landcurrency sync             - Forzar sincronización desde Lands para tu Land");
        player.sendMessage("/landcurrency reload           - Recargar configuración del plugin (permiso helieco.reload)");
        player.sendMessage("/landcurrency help [página]    - Mostrar esta ayuda");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("helieco.reload")) {
            sender.sendMessage("No tienes permiso para recargar el plugin.");
            return true;
        }
        // reload config and managers
        plugin.reloadConfig();
        if (plugin.getBiomeDropManager() != null) plugin.getBiomeDropManager().loadConfig(plugin);
        if (plugin.getWorkPointManager() != null) plugin.getWorkPointManager().loadConfig();
        if (plugin.getCurrencyManager() != null) plugin.getCurrencyManager().loadAll();
        sender.sendMessage("Configuración recargada.");
        return true;
    }

    // Placeholder: intenta determinar la Land del jugador.
    // Devuelve el id como String (puede ser UUID, ULID, etc.).
    // Debe reemplazarse por la llamada real a LandsAPI para comprobar ownership.
    private String tryGetLandOfPlayer(Player player) {
        try {
            LandsIntegration lands = plugin.getLands();
            if (lands == null) return null;

            Object land = null;

            // Intentar métodos comunes en LandsIntegration
            // 1) getLandByChunk(World,int,int)
            try {
                Method m = lands.getClass().getMethod("getLandByChunk", org.bukkit.World.class, int.class, int.class);
                org.bukkit.Chunk c = player.getLocation().getChunk();
                land = m.invoke(lands, player.getWorld(), c.getX(), c.getZ());
            } catch (NoSuchMethodException ignored) {
            }

            // 2) getArea(Location) -> area.getLand()
            if (land == null) {
                try {
                    Method ma = lands.getClass().getMethod("getArea", org.bukkit.Location.class);
                    Object area = ma.invoke(lands, player.getLocation());
                    if (area != null) {
                        try {
                            Method al = area.getClass().getMethod("getLand");
                            land = al.invoke(area);
                        } catch (NoSuchMethodException ignored2) {
                            // fallback: area might itself implement Land-like methods
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }

            if (land == null) {
                plugin.getLogger().warning("No se pudo obtener Land desde LandsIntegration. Métodos disponibles en LandsIntegration:");
                for (Method mm : lands.getClass().getMethods()) {
                    plugin.getLogger().warning(" - " + mm.toString());
                }
                return null;
            }

            Class<?> landCls = land.getClass();

            plugin.getLogger().fine("Land object class=" + landCls.getName());
            plugin.getLogger().fine("Checking ownership for player UUID=" + player.getUniqueId());

                // 1) Intentar isOwner(UUID)
            try {
                Method isOwner = landCls.getMethod("isOwner", java.util.UUID.class);
                Object res = isOwner.invoke(land, player.getUniqueId());
                plugin.getLogger().fine("isOwner(UUID) invocation: " + String.valueOf(res));
                if (res instanceof Boolean && (Boolean) res) {
                        // Prefer methods that return a textual ULID/id
                        String[] preferIdMethods = new String[]{"getULID", "getUlid", "getULIDString", "getUlidString", "getUniqueId", "getId"};
                        for (String mid : preferIdMethods) {
                            try {
                                Method getId = landCls.getMethod(mid);
                                Object id = getId.invoke(land);
                                if (id != null) {
                                    String sid = id.toString();
                                    plugin.getLogger().fine("Using id from method " + mid + " -> " + sid);
                                    // If method returned numeric -1, keep searching
                                    if (sid.equals("-1") || sid.equals("0")) {
                                        continue;
                                    }
                                    return sid;
                                }
                            } catch (NoSuchMethodException ignored) {
                                plugin.getLogger().fine("Method not present on Land: " + mid);
                            }
                        }

                        // Fallback: intentar extraer ULID del toString() del objeto Land
                        try {
                            String repr = land.toString();
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("ulid=([^,}]+)").matcher(repr);
                            if (m.find()) {
                                String ulid = m.group(1);
                                plugin.getLogger().fine("Extracted ULID from toString(): " + ulid);
                                return ulid;
                            }
                        } catch (Throwable ignored) {}

                        return null;
                }
            } catch (NoSuchMethodException ignored) {
                // no public isOwner(UUID)
            }

            // Helper: intenta obtener método público o declarado y hacerlo accesible
            java.util.function.Function<String, Method> findMethod = (String name) -> {
                try {
                    return landCls.getMethod(name);
                } catch (NoSuchMethodException e) {
                    try {
                        Method m = landCls.getDeclaredMethod(name);
                        m.setAccessible(true);
                        return m;
                    } catch (NoSuchMethodException | SecurityException ex) {
                        plugin.getLogger().info("Method not found on Land (helper): " + name);
                        return null;
                    }
                }
            };

            // 2) Intentar getOwnerUID() (algunas versiones exponen directamente el UUID)
            try {
                Method getOwnerUID = findMethod.apply("getOwnerUID");
                if (getOwnerUID != null) {
                    Object ou = getOwnerUID.invoke(land);
                    plugin.getLogger().fine("getOwnerUID() -> " + (ou == null ? "null" : (ou.getClass().getName() + " -> " + ou.toString())));
                    java.util.UUID ownerUuid = null;
                    if (ou instanceof java.util.UUID) ownerUuid = (java.util.UUID) ou;
                    else if (ou instanceof String) {
                        try { ownerUuid = java.util.UUID.fromString((String) ou); } catch (IllegalArgumentException ignored) {}
                    }
                    if (ownerUuid != null && ownerUuid.equals(player.getUniqueId())) {
                        Method getId2 = findMethod.apply("getId");
                        if (getId2 != null) {
                            Object id2 = getId2.invoke(land);
                            if (id2 != null) {
                                String sid = id2.toString();
                                if (sid.equals("-1") || sid.equals("0")) {
                                    // try extracting ULID from toString() as fallback
                                    try {
                                        String repr = land.toString();
                                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("ulid=([^,}]+)").matcher(repr);
                                        if (m.find()) {
                                            String ulid = m.group(1);
                                            plugin.getLogger().fine("Extracted ULID from toString() as fallback: " + ulid);
                                            return ulid;
                                        }
                                    } catch (Throwable ignored) {}
                                    // otherwise return null to indicate not found
                                    return null;
                                }
                                return sid;
                            }
                        }
                        return null;
                    }
                }
            } catch (Throwable ignored) {
            }

            // 3) Intentar getOwner() y extraer UUID de distintas formas
            try {
                Method getOwner = findMethod.apply("getOwner");
                if (getOwner != null) {
                    Object owner = getOwner.invoke(land);
                    plugin.getLogger().fine("getOwner() -> " + (owner == null ? "null" : (owner.getClass().getName() + " -> " + owner.toString())));
                    if (owner != null) {
                        java.util.UUID ownerUuid = null;
                        if (owner instanceof java.util.UUID) ownerUuid = (java.util.UUID) owner;
                        else if (owner instanceof String) {
                            try { ownerUuid = java.util.UUID.fromString((String) owner); } catch (IllegalArgumentException ignored) {}
                        } else {
                            // intentar owner.getUuid() o owner.getUniqueId()
                            try {
                                Method gu = owner.getClass().getMethod("getUuid");
                                Object ou = gu.invoke(owner);
                                plugin.getLogger().fine("owner.getUuid() -> " + (ou == null ? "null" : (ou.getClass().getName() + " -> " + ou.toString())));
                                if (ou instanceof java.util.UUID) ownerUuid = (java.util.UUID) ou;
                                else if (ou instanceof String) ownerUuid = java.util.UUID.fromString((String) ou);
                            } catch (NoSuchMethodException ignored2) {
                                try {
                                    Method gu2 = owner.getClass().getMethod("getUniqueId");
                                    Object ou2 = gu2.invoke(owner);
                                    plugin.getLogger().fine("owner.getUniqueId() -> " + (ou2 == null ? "null" : (ou2.getClass().getName() + " -> " + ou2.toString())));
                                    if (ou2 instanceof java.util.UUID) ownerUuid = (java.util.UUID) ou2;
                                    else if (ou2 instanceof String) ownerUuid = java.util.UUID.fromString((String) ou2);
                                } catch (NoSuchMethodException ignored3) {
                                }
                            }
                        }

                        if (ownerUuid != null && ownerUuid.equals(player.getUniqueId())) {
                            Method getId2 = findMethod.apply("getId");
                            if (getId2 != null) {
                                    Object id2 = getId2.invoke(land);
                                    if (id2 != null) {
                                        String sid = id2.toString();
                                        if (sid.equals("-1") || sid.equals("0")) {
                                            try {
                                                String repr = land.toString();
                                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("ulid=([^,}]+)").matcher(repr);
                                                if (m.find()) {
                                                    String ulid = m.group(1);
                                                    plugin.getLogger().fine("Extracted ULID from toString() as fallback: " + ulid);
                                                    return ulid;
                                                }
                                            } catch (Throwable ignored) {}
                                            return null;
                                        }
                                        return sid;
                                    }
                            }
                            return null;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // Fallback de administrador/operador: permitir si tiene permiso
            try {
                Method getIdFallback = findMethod.apply("getId");
                if (getIdFallback != null) {
                    Object idObj = getIdFallback.invoke(land);
                    if (idObj != null) {
                        String possibleId = idObj.toString();
                        // Reject sentinel values like -1 or 0 even for admin/op; try extracting ULID from toString() instead
                        if (possibleId.equals("-1") || possibleId.equals("0")) {
                            plugin.getLogger().fine("Fallback getId() returned sentinel value '" + possibleId + "' for land; attempting to extract ULID from toString() instead.");
                            try {
                                String repr = land.toString();
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("ulid=([^,}]+)").matcher(repr);
                                if (m.find()) {
                                    String ulid = m.group(1);
                                    plugin.getLogger().fine("Extracted ULID from toString() as fallback: " + ulid);
                                    return ulid;
                                }
                            } catch (Throwable ignored2) {}
                            // if we couldn't extract, do not return the sentinel id
                            plugin.getLogger().warning("Admin fallback getId() returned invalid id and no ULID could be extracted; refusing to use '" + possibleId + "'.");
                            return null;
                        }

                        if (player.hasPermission("helieco.admin") || player.isOp()) {
                            plugin.getLogger().fine("Player " + player.getName() + " has admin/op permission; granting Land id " + possibleId + " as fallback.");
                            return possibleId;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            plugin.getLogger().warning("No se pudo determinar ownership for Land (reduced logging). Enable DEBUG for details.");
            plugin.getLogger().fine(() -> {
                StringBuilder sb = new StringBuilder();
                for (Method mm : landCls.getMethods()) sb.append(" - ").append(mm.toString()).append('\n');
                return sb.toString();
            });

            return null;
        } catch (Throwable t) {
            plugin.getLogger().warning("Error comprobando Land ownership: " + t.getMessage());
            return null;
        }
    }
}
