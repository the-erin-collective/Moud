package com.moud.plugin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin Discovery Service
 * 
 * Discovers plugins dynamically and validates that all required plugins are present.
 * Uses the BridgePlugin enum for type safety and validation.
 * 
 * This service ensures that:
 * - All plugins defined in the BridgePlugin enum are discovered at startup
 * - No unknown plugins are loaded (prevents typos/duplicates)
 * - Plugin discovery failures result in fatal exceptions
 */
public class PluginDiscoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginDiscoveryService.class);
    
    private static PluginDiscoveryService instance;
    private final Map<String, BridgePlugin> discoveredPlugins = new ConcurrentHashMap<>();
    private final Set<String> discoveredPluginIds = ConcurrentHashMap.newKeySet();
    private boolean discoveryComplete = false;
    
    private PluginDiscoveryService() {
        // Private constructor for singleton
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized PluginDiscoveryService getInstance() {
        if (instance == null) {
            instance = new PluginDiscoveryService();
        }
        return instance;
    }
    
    /**
     * Discover a plugin by its ID and validate it against the enum
     * 
     * @param pluginId The plugin ID from plugin.yml
     * @throws IllegalArgumentException if the plugin ID is not recognized
     */
    public synchronized void discoverPlugin(String pluginId) {
        if (discoveryComplete) {
            throw new IllegalStateException("Plugin discovery is already complete. Cannot discover new plugins.");
        }
        
        LOGGER.info("[PluginDiscovery] Discovering plugin: {}", pluginId);
        
        try {
            BridgePlugin bridgePlugin = BridgePlugin.findByPluginId(pluginId);
            
            if (discoveredPlugins.containsKey(pluginId)) {
                LOGGER.warn("[PluginDiscovery] Plugin {} already discovered - duplicate discovery attempt", pluginId);
                return;
            }
            
            discoveredPlugins.put(pluginId, bridgePlugin);
            discoveredPluginIds.add(pluginId);
            
            LOGGER.info("[PluginDiscovery] Successfully discovered: {} -> {}", pluginId, bridgePlugin.getBridgeName());
            
        } catch (IllegalArgumentException e) {
            LOGGER.error("[PluginDiscovery] ❌ FATAL: Unknown plugin ID '{}' discovered. This plugin is not defined in BridgePlugin enum.", pluginId);
            LOGGER.error("[PluginDiscovery] Available plugins in enum: {}", Arrays.toString(BridgePlugin.values()));
            throw new RuntimeException("FATAL: Unknown plugin discovered: " + pluginId + ". All plugins must be defined in BridgePlugin enum.", e);
        }
    }
    
    /**
     * Complete the discovery process and validate that all required plugins are present
     * 
     * @throws RuntimeException if any required plugins are missing
     */
    public synchronized void completeDiscovery() {
        if (discoveryComplete) {
            LOGGER.warn("[PluginDiscovery] Discovery already completed");
            return;
        }
        
        LOGGER.info("[PluginDiscovery] Completing plugin discovery...");
        LOGGER.info("[PluginDiscovery] Discovered plugins: {}", discoveredPluginIds);
        
        // Check for missing plugins
        List<BridgePlugin> missingPlugins = new ArrayList<>();
        for (BridgePlugin expectedPlugin : BridgePlugin.values()) {
            if (!discoveredPlugins.containsKey(expectedPlugin.getPluginId())) {
                missingPlugins.add(expectedPlugin);
            }
        }
        
        if (!missingPlugins.isEmpty()) {
            LOGGER.error("[PluginDiscovery] ❌ FATAL: Missing required plugins:");
            for (BridgePlugin missing : missingPlugins) {
                LOGGER.error("[PluginDiscovery]   - {} (plugin ID: {})", missing.getBridgeName(), missing.getPluginId());
            }
            LOGGER.error("[PluginDiscovery] Discovered plugins: {}", discoveredPluginIds);
            LOGGER.error("[PluginDiscovery] Expected plugins: {}", Arrays.toString(BridgePlugin.getAllPluginIds()));
            throw new RuntimeException("FATAL: Missing required bridge plugins: " + missingPlugins + 
                ". All plugins defined in BridgePlugin enum must be present at startup.");
        }
        
        discoveryComplete = true;
        LOGGER.info("[PluginDiscovery] ✅ Plugin discovery completed successfully!");
        LOGGER.info("[PluginDiscovery] All {} required plugins discovered and validated:", discoveredPlugins.size());
        for (Map.Entry<String, BridgePlugin> entry : discoveredPlugins.entrySet()) {
            LOGGER.info("[PluginDiscovery]   - {} -> {}", entry.getKey(), entry.getValue().getBridgeName());
        }
    }
    
    /**
     * Get the BridgePlugin enum for a discovered plugin
     * 
     * @param pluginId The plugin ID
     * @return The BridgePlugin enum value
     * @throws IllegalArgumentException if plugin was not discovered
     */
    public BridgePlugin getDiscoveredPlugin(String pluginId) {
        if (!discoveryComplete) {
            throw new IllegalStateException("Plugin discovery not complete. Call completeDiscovery() first.");
        }
        
        BridgePlugin plugin = discoveredPlugins.get(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin not discovered: " + pluginId);
        }
        return plugin;
    }
    
    /**
     * Get all discovered plugin IDs
     */
    public Set<String> getDiscoveredPluginIds() {
        return new HashSet<>(discoveredPluginIds);
    }
    
    /**
     * Get all discovered BridgePlugin enums
     */
    public Collection<BridgePlugin> getDiscoveredPlugins() {
        return new ArrayList<>(discoveredPlugins.values());
    }
    
    /**
     * Check if discovery is complete
     */
    public boolean isDiscoveryComplete() {
        return discoveryComplete;
    }
    
    /**
     * Check if a specific plugin was discovered
     */
    public boolean isPluginDiscovered(String pluginId) {
        return discoveredPluginIds.contains(pluginId);
    }
    
    /**
     * Get the bridge name for a discovered plugin
     */
    public String getBridgeName(String pluginId) {
        BridgePlugin plugin = getDiscoveredPlugin(pluginId);
        return plugin.getBridgeName();
    }
    
    /**
     * Reset the discovery service (for testing/restart scenarios)
     */
    synchronized void reset() {
        discoveredPlugins.clear();
        discoveredPluginIds.clear();
        discoveryComplete = false;
        LOGGER.info("[PluginDiscovery] Discovery service reset");
    }
}
