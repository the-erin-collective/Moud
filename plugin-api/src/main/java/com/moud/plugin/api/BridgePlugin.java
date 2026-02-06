package com.moud.plugin.api;

/**
 * Enum representing all known bridge plugins that should be available.
 * This provides type safety for plugin references and validation.
 * 
 * Each enum value maps to a plugin ID that should be discovered at runtime.
 * If any enum-mapped plugin is not discovered during startup, a fatal exception is thrown.
 */
public enum BridgePlugin {
    BASE("base-bridge-plugin", "Base"),
    TERRA("terra-bridge-plugin", "Terra"), 
    TROVE("trove-bridge-plugin", "Trove"),
    PVP("pvp-bridge-plugin", "PvP"),
    POLAR("polar-bridge-plugin", "Polar"),
    ENDLESS("endless-bridge-plugin", "Endless");
    
    private final String pluginId;
    private final String bridgeName;
    
    BridgePlugin(String pluginId, String bridgeName) {
        this.pluginId = pluginId;
        this.bridgeName = bridgeName;
    }
    
    /**
     * Get the plugin ID as defined in plugin.yml
     */
    public String getPluginId() {
        return pluginId;
    }
    
    /**
     * Get the bridge name used for registration with BridgeRegistry
     */
    public String getBridgeName() {
        return bridgeName;
    }
    
    /**
     * Find a BridgePlugin by its plugin ID
     */
    public static BridgePlugin findByPluginId(String pluginId) {
        for (BridgePlugin plugin : values()) {
            if (plugin.pluginId.equals(pluginId)) {
                return plugin;
            }
        }
        throw new IllegalArgumentException("Unknown plugin ID: " + pluginId);
    }
    
    /**
     * Find a BridgePlugin by its bridge name
     */
    public static BridgePlugin findByBridgeName(String bridgeName) {
        for (BridgePlugin plugin : values()) {
            if (plugin.bridgeName.equals(bridgeName)) {
                return plugin;
            }
        }
        throw new IllegalArgumentException("Unknown bridge name: " + bridgeName);
    }
    
    /**
     * Get all expected plugin IDs
     */
    public static String[] getAllPluginIds() {
        String[] ids = new String[values().length];
        for (int i = 0; i < values().length; i++) {
            ids[i] = values()[i].pluginId;
        }
        return ids;
    }
    
    /**
     * Get all expected bridge names
     */
    public static String[] getAllBridgeNames() {
        String[] names = new String[values().length];
        for (int i = 0; i < values().length; i++) {
            names[i] = values()[i].bridgeName;
        }
        return names;
    }
    
    @Override
    public String toString() {
        return String.format("BridgePlugin{id='%s', bridge='%s'}", pluginId, bridgeName);
    }
}
