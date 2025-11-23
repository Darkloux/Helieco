package com.helixteam.economyplugin.currency;

 

/**
 * Representa la moneda asociada a una Land.
 * Persistible via YAML por Land.
 */
public class LandCurrency {

    private final String landId;
    private String name;
    private java.math.BigDecimal bankBalance;
    private int issuedCount = 0;

    public LandCurrency(String landId, String name) {
        this.landId = landId == null ? "" : landId;
        this.name = name;
        this.bankBalance = java.math.BigDecimal.ZERO;
    }

    public String getLandId() {
        return landId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public java.math.BigDecimal getBankBalance() {
        return bankBalance;
    }

    public void setBankBalance(java.math.BigDecimal bankBalance) {
        this.bankBalance = bankBalance == null ? java.math.BigDecimal.ZERO : bankBalance;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public void addIssued(int n) {
        if (n <= 0) return;
        issuedCount += n;
    }

    public boolean removeOneIssued() {
        if (issuedCount <= 0) return false;
        issuedCount -= 1;
        return true;
    }

    public void setIssuedCount(int c) {
        this.issuedCount = Math.max(0, c);
    }
}
