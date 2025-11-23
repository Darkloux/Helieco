package com.helixteam.economyplugin.currency;

import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;

public class BillItemFactory {

    public ItemStack createBill(LandCurrency currency, java.math.BigDecimal value, LocalDate expireDate) {
        // Obtener material desde configuración global (o usar PAPER por defecto)
        var plugin = com.helixteam.economyplugin.EconomyPlugin.getInstance();
        String matName = plugin.getConfig().getString("currency.default_item", "PAPER");
        org.bukkit.Material mat;
        try {
            mat = org.bukkit.Material.valueOf(matName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            mat = org.bukkit.Material.PAPER;
        }

        ItemStack item = new ItemStack(mat);

        // Crear BillData (no id para permitir stackeo)
        String landId = currency.getLandId();
        String issue = LocalDate.now().toString();
        String expire = expireDate == null ? "" : expireDate.toString();
        BillData data = new BillData("", landId, value, issue, expire, mat.name());

        // Poner display name y lore (legible) y guardar en PDC
        var meta = item.getItemMeta();
        if (meta != null) {
            String pretty = value == null ? "0" : value.stripTrailingZeros().toPlainString();
            // Display name debe ser solo el nombre de la moneda
            meta.setDisplayName(currency.getName() == null || currency.getName().isEmpty() ? "Land" : currency.getName());
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add("Emisor: " + (currency.getName() == null || currency.getName().isEmpty() ? "-" : currency.getName()));
            lore.add("Valor: " + pretty);
            if (!expire.isEmpty()) lore.add("Vence: " + expire);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        // Escribir datos en PDC
        data.writeToItem(item, plugin);

        // No registrar cada billete individualmente; el contador se gestiona en CurrencyManager/CurrencyCommands

        return item;
    }
}
