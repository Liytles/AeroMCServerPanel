package com.aerogroup.mcpanel;

/** JAR manifestinden tek merkezli uygulama sürümünü okur. */
public final class BuildInfo {
    private BuildInfo() { }
    public static String version() { String value = BuildInfo.class.getPackage().getImplementationVersion(); return value == null || value.isBlank() ? "development" : value; }
    public static String displayVersion() { return "development".equals(version()) ? "Development" : "v" + version(); }
}
