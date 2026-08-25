package com.aerogroup.mcpanel;

public final class SparkInstallerSmoke {
    public static void main(String[] args) {
        require(SparkInstaller.supports("Paper", "1.20.6"), "old Paper supported"); require(SparkInstaller.supports("Fabric", "1.19.4"), "old Fabric supported"); require(!SparkInstaller.supports("Paper", "1.21"), "Paper 1.21 bundled"); require(!SparkInstaller.supports("Vanilla", "1.20.4"), "Vanilla rejected"); require(!SparkInstaller.isBefore121("not-a-version"), "invalid version rejected");
        System.out.println("spark-installer-rules-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
