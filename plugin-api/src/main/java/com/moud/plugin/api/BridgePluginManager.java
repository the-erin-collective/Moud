package com.moud.plugin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages synchronized initialization barrier between Java bridge plugins and JavaScript runtime.
 * 
 * This ensures that JavaScript waits for all Java bridge plugins to fully load before
 * initializing, preventing race conditions and missing bridge services.
 * 
 * Similar to Promise.all() in JavaScript - Java plugins can signal readiness individually,
 * and JavaScript initialization only proceeds after all required bridges are available.
 */
public class BridgePluginManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BridgePluginManager.class);
    
    // Default expected bridge plugins - can be configured dynamically
    private static volatile Set<String> expectedPlugins = Set.of("trove", "terra", "polar", "pvp", "base");
    private static final Set<String> loadedPlugins = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean initializationComplete = new AtomicBoolean(false);
    private static final AtomicBoolean initializationStarted = new AtomicBoolean(false);
    
    // For async waiting mechanism
    private static volatile CompletableFuture<Void> readinessFuture = null;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BridgePluginManager-Scheduler");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Sets the expected bridge plugin IDs. This should be called before plugin loading begins.
     * 
     * @param pluginIds Set of plugin IDs that are expected to register bridge services
     */
    public static synchronized void setExpectedPlugins(Set<String> pluginIds) {
        if (initializationStarted.get()) {
            LOGGER.warn("Cannot change expected plugins after initialization has started");
            return;
        }
        
        expectedPlugins = Set.copyOf(pluginIds);
        LOGGER.info("Expected bridge plugins set to: {}", expectedPlugins);
    }
    
    /**
     * Gets the current set of expected plugin IDs.
     * 
     * @return Set of expected plugin IDs
     */
    public static Set<String> getExpectedPlugins() {
        return expectedPlugins;
    }
    
    /**
     * Called by each bridge plugin after it has registered its bridge services.
     * This marks the plugin as ready for JavaScript consumption.
     * 
     * @param pluginId The unique identifier of the bridge plugin
     */
    public static synchronized void markPluginReady(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin ID cannot be null or empty");
        }
        
        String normalizedId = pluginId.trim();
        
        if (loadedPlugins.contains(normalizedId)) {
            LOGGER.warn("Plugin '{}' was already marked as ready", normalizedId);
            return;
        }
        
        loadedPlugins.add(normalizedId);
        LOGGER.info("[BridgePluginManager] Plugin marked ready: {} ({}/{})", 
                   normalizedId, loadedPlugins.size(), expectedPlugins.size());
        
        // Check if all plugins are ready
        if (allPluginsReady() && !initializationComplete.get()) {
            completeInitialization();
        }
    }
    
    /**
     * Checks if all expected bridge plugins have been marked as ready.
     * 
     * @return true if all expected plugins are ready, false otherwise
     */
    public static synchronized boolean allPluginsReady() {
        return loadedPlugins.containsAll(expectedPlugins);
    }
    
    /**
     * Gets the current set of loaded plugin IDs.
     * 
     * @return Set of loaded plugin IDs
     */
    public static Set<String> getLoadedPlugins() {
        return Set.copyOf(loadedPlugins);
    }
    
    /**
     * Gets the set of expected plugins that haven't loaded yet.
     * 
     * @return Set of pending plugin IDs
     */
    public static synchronized Set<String> getPendingPlugins() {
        Set<String> pending = ConcurrentHashMap.newKeySet();
        for (String plugin : expectedPlugins) {
            if (!loadedPlugins.contains(plugin)) {
                pending.add(plugin);
            }
        }
        return pending;
    }
    
    /**
     * Returns a CompletableFuture that completes when all bridge plugins are ready.
     * This is the preferred way for JavaScript runtime to wait for plugin readiness.
     * 
     * @return CompletableFuture that completes when all plugins are ready
     */
    public static synchronized CompletableFuture<Void> getReadinessFuture() {
        if (readinessFuture == null) {
            initializationStarted.set(true);
            readinessFuture = new CompletableFuture<>();
            
            if (allPluginsReady()) {
                // If all plugins are already ready, complete immediately
                completeInitialization();
            } else {
                LOGGER.info("[BridgePluginManager] Waiting for {} plugins: {}", 
                           getPendingPlugins().size(), getPendingPlugins());
                
                // Start a periodic check as a fallback
                scheduler.scheduleAtFixedRate(() -> {
                    if (allPluginsReady() && !initializationComplete.get()) {
                        completeInitialization();
                    }
                }, 50, 50, TimeUnit.MILLISECONDS);
            }
        }
        
        return readinessFuture;
    }
    
    /**
     * Legacy callback-based method for backward compatibility.
     * Executes the callback when all plugins are ready.
     * 
     * @param callback Runnable to execute when all plugins are ready
     */
    public static void waitForAllPlugins(Runnable callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        
        getReadinessFuture().thenRun(callback);
    }
    
    /**
     * Checks if the bridge plugin initialization is complete.
     * 
     * @return true if initialization is complete, false otherwise
     */
    public static boolean isInitializationComplete() {
        return initializationComplete.get();
    }
    
    /**
     * Resets the manager state. This should only be used for testing or server restart scenarios.
     */
    static synchronized void reset() {
        loadedPlugins.clear();
        initializationComplete.set(false);
        initializationStarted.set(false);
        readinessFuture = null;
        LOGGER.info("[BridgePluginManager] Reset completed");
    }
    
    /**
     * Internal method to mark initialization as complete and trigger callbacks.
     */
    private static void completeInitialization() {
        if (initializationComplete.compareAndSet(false, true)) {
            LOGGER.info("[BridgePluginManager] All bridge plugins ready! Loaded {}/{} plugins: {}", 
                       loadedPlugins.size(), expectedPlugins.size(), loadedPlugins);
            
            if (readinessFuture != null) {
                readinessFuture.complete(null);
            }
        }
    }
    
    /**
     * Shutdown cleanup for the scheduler.
     */
    static void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
