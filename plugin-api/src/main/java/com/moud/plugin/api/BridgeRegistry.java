package com.moud.plugin.api;

import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry for bridge services that connect Java plugins to the JavaScript runtime.
 * 
 * Plugins can register their bridge services using {@link #register(String, Object)},
 * and these services will be automatically injected into the JavaScript global scope
 * during runtime initialization.
 * 
 * Registered services should use {@link HostAccess.Export} annotations on methods
 * that should be accessible from JavaScript.
 */
public class BridgeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeRegistry.class);
    
    private static final Map<String, Object> services = new ConcurrentHashMap<>();
    private static final Map<String, String> serviceOwners = new ConcurrentHashMap<>();
    
    /**
     * Registers a bridge service that can be accessed from JavaScript.
     * 
     * @param id The unique identifier for the bridge service (will become the global variable name in JS)
     * @param service The service object containing methods annotated with @HostAccess.Export
     * @param pluginId The ID of the plugin registering this service (for tracking/debugging)
     * @throws IllegalArgumentException if id is null/blank or service is null
     * @throws IllegalStateException if a service with the same ID is already registered
     */
    public static void register(String id, Object service, String pluginId) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Bridge service ID cannot be null or blank");
        }
        if (service == null) {
            throw new IllegalArgumentException("Bridge service cannot be null");
        }
        if (pluginId == null || pluginId.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin ID cannot be null or blank");
        }
        
        String normalizedId = id.trim();
        
        synchronized (services) {
            if (services.containsKey(normalizedId)) {
                String existingOwner = serviceOwners.get(normalizedId);
                throw new IllegalStateException(
                    String.format("Bridge service '%s' is already registered by plugin '%s'", 
                                normalizedId, existingOwner)
                );
            }
            
            services.put(normalizedId, service);
            serviceOwners.put(normalizedId, pluginId);
            
            LOGGER.info("Registered bridge service '{}' from plugin '{}'", normalizedId, pluginId);
        }
    }
    
    /**
     * Retrieves a registered bridge service.
     * 
     * @param id The identifier of the service to retrieve
     * @return The service object, or null if not found
     */
    public static Object get(String id) {
        if (id == null) {
            return null;
        }
        return services.get(id.trim());
    }
    
    /**
     * Unregisters a bridge service.
     * 
     * @param id The identifier of the service to unregister
     * @param pluginId The ID of the plugin attempting to unregister (for security)
     * @return true if the service was unregistered, false if not found or owned by different plugin
     */
    public static boolean unregister(String id, String pluginId) {
        if (id == null || pluginId == null) {
            return false;
        }
        
        String normalizedId = id.trim();
        
        synchronized (services) {
            String existingOwner = serviceOwners.get(normalizedId);
            if (existingOwner == null || !existingOwner.equals(pluginId)) {
                LOGGER.warn("Plugin '{}' attempted to unregister service '{}' owned by '{}'", 
                           pluginId, normalizedId, existingOwner);
                return false;
            }
            
            services.remove(normalizedId);
            serviceOwners.remove(normalizedId);
            
            LOGGER.info("Unregistered bridge service '{}' from plugin '{}'", normalizedId, pluginId);
            return true;
        }
    }
    
    /**
     * Gets all registered bridge services.
     * 
     * @return A copy of the services map
     */
    public static Map<String, Object> getAllServices() {
        synchronized (services) {
            return new ConcurrentHashMap<>(services);
        }
    }
    
    /**
     * Checks if a service is registered.
     * 
     * @param id The identifier to check
     * @return true if the service is registered, false otherwise
     */
    public static boolean isRegistered(String id) {
        if (id == null) {
            return false;
        }
        return services.containsKey(id.trim());
    }
    
    /**
     * Gets the owner plugin ID for a registered service.
     * 
     * @param id The service identifier
     * @return The plugin ID that owns the service, or null if not found
     */
    public static String getOwner(String id) {
        if (id == null) {
            return null;
        }
        return serviceOwners.get(id.trim());
    }
    
    /**
     * Clears all registered services. This should typically only be called during server shutdown.
     */
    public static void clear() {
        synchronized (services) {
            int count = services.size();
            services.clear();
            serviceOwners.clear();
            LOGGER.info("Cleared {} registered bridge services", count);
        }
    }
}
