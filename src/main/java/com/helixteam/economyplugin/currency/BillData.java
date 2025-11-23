package com.helixteam.economyplugin.currency;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.UUID;

import com.helixteam.economyplugin.EconomyPlugin;

public class BillData {

    private final String id;
    private final String landId;
    private java.math.BigDecimal value;
    private String issueDate; // ISO
    private String expireDate; // ISO
    private String material; // material used for bill item (e.g., PAPER)

    public BillData(String id, String landId, java.math.BigDecimal value, String issueDate, String expireDate, String material) {
        this.id = id;
        this.landId = landId;
        this.value = value == null ? java.math.BigDecimal.ZERO : value;
        this.issueDate = issueDate;
        this.expireDate = expireDate;
        this.material = material;
    }

    public static BillData fromSection(ConfigurationSection sec) {
        String id = sec.getString("id", UUID.randomUUID().toString());
        String landId = sec.getString("landId", "");
        java.math.BigDecimal value;
        if (sec.isString("value")) {
            try {
                value = new java.math.BigDecimal(sec.getString("value"));
            } catch (Exception e) {
                value = java.math.BigDecimal.valueOf(sec.getDouble("value", 0.0));
            }
        } else {
            value = java.math.BigDecimal.valueOf(sec.getDouble("value", 0.0));
        }
        String issueDate = sec.getString("issueDate", LocalDate.now().toString());
        String expireDate = sec.getString("expireDate", "");
        String material = sec.getString("material", "PAPER");
        return new BillData(id, landId, value, issueDate, expireDate, material);
    }

    public void saveToSection(ConfigurationSection sec) {
        sec.set("id", id);
        sec.set("landId", landId);
        // Store value as string to preserve precision
        sec.set("value", value.toPlainString());
        sec.set("issueDate", issueDate);
        sec.set("expireDate", expireDate);
        sec.set("material", material);
    }

    /**
     * Escribe los campos del billete en el ItemStack usando PersistentDataContainer.
     */
    public void writeToItem(ItemStack item, EconomyPlugin plugin) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer c = meta.getPersistentDataContainer();
        // Do NOT write a unique id to allow stacking; detect bills by land id
        c.set(plugin.key("helieco_bill_land"), PersistentDataType.STRING, landId);
        // store as string in PDC to preserve precision
        c.set(plugin.key("helieco_bill_value"), PersistentDataType.STRING, value.toPlainString());
        c.set(plugin.key("helieco_bill_issue"), PersistentDataType.STRING, issueDate);
        c.set(plugin.key("helieco_bill_expire"), PersistentDataType.STRING, expireDate == null ? "" : expireDate);
        c.set(plugin.key("helieco_bill_material"), PersistentDataType.STRING, material);

        item.setItemMeta(meta);
    }

    /**
     * Intenta leer un BillData desde un ItemStack que tenga los PDC correspondientes.
     * Devuelve null si no contiene la información necesaria.
     */
    public static BillData fromItem(ItemStack item, EconomyPlugin plugin) {
        if (item == null || item.getItemMeta() == null) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer c = meta.getPersistentDataContainer();

        NamespacedKey kLand = plugin.key("helieco_bill_land");
        NamespacedKey kValue = plugin.key("helieco_bill_value");
        NamespacedKey kIssue = plugin.key("helieco_bill_issue");
        NamespacedKey kExpire = plugin.key("helieco_bill_expire");
        NamespacedKey kMat = plugin.key("helieco_bill_material");
        if (!c.has(kLand, PersistentDataType.STRING)) return null;
        String landId = c.get(kLand, PersistentDataType.STRING);
        String id = "";
        java.math.BigDecimal value;
        if (c.has(kValue, PersistentDataType.STRING)) {
            try {
                value = new java.math.BigDecimal(c.get(kValue, PersistentDataType.STRING));
            } catch (Exception e) {
                value = java.math.BigDecimal.ZERO;
            }
        } else if (c.has(kValue, PersistentDataType.DOUBLE)) {
            value = java.math.BigDecimal.valueOf(c.get(kValue, PersistentDataType.DOUBLE));
        } else {
            value = java.math.BigDecimal.ZERO;
        }
        String issue = c.has(kIssue, PersistentDataType.STRING) ? c.get(kIssue, PersistentDataType.STRING) : LocalDate.now().toString();
        String expire = c.has(kExpire, PersistentDataType.STRING) ? c.get(kExpire, PersistentDataType.STRING) : "";
        String material = c.has(kMat, PersistentDataType.STRING) ? c.get(kMat, PersistentDataType.STRING) : item.getType().name();

        return new BillData(id, landId, value, issue, expire, material);
    }

    public String getId() {
        return id;
    }

    public String getLandId() {
        return landId;
    }

    public java.math.BigDecimal getValue() {
        return value;
    }

    public void setValue(java.math.BigDecimal value) {
        this.value = value == null ? java.math.BigDecimal.ZERO : value;
    }

    public String getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(String issueDate) {
        this.issueDate = issueDate;
    }

    public String getExpireDate() {
        return expireDate;
    }

    public void setExpireDate(String expireDate) {
        this.expireDate = expireDate;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }
}
