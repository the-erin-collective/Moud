package com.moud.server.plugin;

import com.moud.plugin.api.Plugin;
import com.moud.plugin.api.PluginApi;
import com.moud.plugin.api.BridgePluginManager;
import com.moud.server.assets.AssetsExtractor;
import com.moud.server.plugin.context.PluginContextImpl;
import com.moud.server.plugin.core.DependencyResolver;
import com.moud.server.plugin.core.PluginClassLoader;
import com.moud.server.plugin.core.PluginContainer;
import com.moud.server.plugin.core.PluginDescription;
import com.moud.server.plugin.core.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class PluginLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginLoader.class);
    private static Path PLUGINS_DIR;

    private final PluginManager manager;
    
    // Plugin loading statistics
    private int totalJarsScanned = 0;
    private int pluginsDetected = 0;
    private int pluginsLoaded = 0;
    private int pluginsFailed = 0;
    private final Set<String> skippedFiles = new HashSet<>();
    private final List<String> loadingErrors = new ArrayList<>();

    public PluginLoader(PluginManager manager) {
        this.manager = manager;
        PLUGINS_DIR = manager.getPluginDir();
    }

    /**
     * Load only the assets of all plugins present in the plugins directory.
     * <p>
     * For each plugin JAR that contains an {@code assets/} folder, its content is copied to:
     * <pre>
     *   projectRoot/assets/&lt;jarFileNameWithoutExtension&gt;/
     * </pre>
     *
     * Behavior:
     * <ul>
     *     <li>Does not delete any existing directories.</li>
     *     <li>Replaces only the files that are present in the JAR (using {@code REPLACE_EXISTING}).</li>
     *     <li>Leaves any extra/custom files (not present in the JAR) untouched.</li>
     * </ul>
     */
    public void loadAssets() {
        LOGGER.info("[PluginLoader] Starting asset loading from directory: {}", PLUGINS_DIR);
        
        if (!Files.isDirectory(PLUGINS_DIR)) {
            LOGGER.warn("[PluginLoader] Plugins directory does not exist or is not a directory: {}", PLUGINS_DIR);
            LOGGER.info("[PluginLoader] Create the plugins directory at: {} to load plugins", PLUGINS_DIR);
            return;
        }

        int assetsFound = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PLUGINS_DIR, "*.jar")) {
            for (Path jarPath : stream) {
                totalJarsScanned++;
                LOGGER.debug("[PluginLoader] Scanning JAR for assets: {}", jarPath.getFileName());
                
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    JarEntry assetsEntry = jar.getJarEntry("assets/");
                    if (assetsEntry != null && assetsEntry.isDirectory()) {
                        assetsFound++;
                        LOGGER.info("[PluginLoader] Assets folder detected in plugin JAR: {}", jarPath.getFileName());
                        try {
                            AssetsExtractor.copyAssetsFromJar(jar, jarPath, manager.getProjectRoot());
                            LOGGER.info("[PluginLoader] Successfully extracted assets from: {}", jarPath.getFileName());
                        } catch (IOException e) {
                            String error = String.format("Failed to extract assets from %s: %s", jarPath.getFileName(), e.getMessage());
                            LOGGER.error("[PluginLoader] {}", error, e);
                            loadingErrors.add(error);
                        }
                    } else {
                        LOGGER.debug("[PluginLoader] No assets folder found in: {}", jarPath.getFileName());
                    }
                } catch (IOException e) {
                    String error = String.format("Error reading JAR file %s: %s", jarPath.getFileName(), e.getMessage());
                    LOGGER.error("[PluginLoader] {}", error, e);
                    loadingErrors.add(error);
                }
            }
        } catch (IOException e) {
            LOGGER.error("[PluginLoader] Error while scanning plugins directory for assets", e);
            loadingErrors.add("Directory scan error: " + e.getMessage());
        }
        
        LOGGER.info("[PluginLoader] Asset loading complete. Scanned {} JAR files, found assets in {} files", totalJarsScanned, assetsFound);
    }

    /**
     * Load and enable all plugins (without handling assets).
     * <p>
     * Steps:
     * <ol>
     *     <li>Scan the plugins directory for {@code *.jar} files.</li>
     *     <li>Inspect each JAR to read {@code plugin.yml} and build a {@link PluginContainer}.</li>
     *     <li>Resolve dependencies between plugins using {@link DependencyResolver}.</li>
     *     <li>Instantiate each plugin and call its lifecycle ({@code enable()}).</li>
     *     <li>Track bridge plugin readiness for JavaScript synchronization.</li>
     * </ol>
     * <p>
     */
    public void loadPlugins() {
        LOGGER.info("[PluginLoader] =======================================");
        LOGGER.info("[PluginLoader] Starting Java Plugin Loading System");
        LOGGER.info("[PluginLoader] Plugin Directory: {}", PLUGINS_DIR);
        LOGGER.info("[PluginLoader] Target API Version: {}", PluginApi.API_VERSION);
        LOGGER.info("[PluginLoader] =======================================");
        
        // Reset statistics
        totalJarsScanned = 0;
        pluginsDetected = 0;
        pluginsLoaded = 0;
        pluginsFailed = 0;
        skippedFiles.clear();
        loadingErrors.clear();
        
        // Initialize bridge plugin tracking
        Set<String> discoveredPluginIds = new HashSet<>();
        
        try {
            List<PluginContainer> discovered = new ArrayList<>();
            
            if (!Files.isDirectory(PLUGINS_DIR)) {
                LOGGER.warn("[PluginLoader] ⚠ Plugins directory does not exist: {}", PLUGINS_DIR);
                LOGGER.info("[PluginLoader] 💡 Create the directory and place .jar files to load plugins");
                printPluginLoadingSummary();
                return;
            }
            
            // Scan for JAR files
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(PLUGINS_DIR, "*.jar")) {
                for (Path jarPath : ds) {
                    totalJarsScanned++;
                    LOGGER.info("[PluginLoader] 📦 Scanning JAR: {}", jarPath.getFileName());
                    
                    try {
                        PluginContainer pc = inspectJar(jarPath);
                        if (pc != null) {
                            pluginsDetected++;
                            discoveredPluginIds.add(pc.getDescription().id);
                            LOGGER.info("[PluginLoader] ✅ Plugin detected: {} v{} (ID: {})", 
                                pc.getDescription().name, 
                                pc.getDescription().version, 
                                pc.getDescription().id);
                            discovered.add(pc);
                        } else {
                            LOGGER.warn("[PluginLoader] ❌ Not a valid plugin: {}", jarPath.getFileName());
                            skippedFiles.add(jarPath.getFileName().toString());
                        }
                    } catch (Exception e) {
                        pluginsFailed++;
                        String error = String.format("Failed to inspect %s: %s", jarPath.getFileName(), e.getMessage());
                        LOGGER.error("[PluginLoader] ❌ {}", error, e);
                        loadingErrors.add(error);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("[PluginLoader] ❌ Failed to scan plugins directory", e);
                loadingErrors.add("Directory scan failed: " + e.getMessage());
            }
            
            LOGGER.info("[PluginLoader] 📊 Scan Results: {} JARs found, {} plugins detected, {} skipped", 
                totalJarsScanned, pluginsDetected, skippedFiles.size());
            
            // Configure bridge plugin manager with discovered plugins
            if (!discoveredPluginIds.isEmpty()) {
                BridgePluginManager.setExpectedPlugins(discoveredPluginIds);
                LOGGER.info("[PluginLoader] 🔗 Bridge plugin manager configured with {} expected plugins: {}", 
                           discoveredPluginIds.size(), discoveredPluginIds);
            }
            
            if (discovered.isEmpty()) {
                LOGGER.warn("[PluginLoader] ⚠ No valid plugins found to load");
                if (!skippedFiles.isEmpty()) {
                    LOGGER.info("[PluginLoader] ℹ️ Skipped files: {}", String.join(", ", skippedFiles));
                }
                printPluginLoadingSummary();
                return;
            }
            
            // Resolve dependencies
            LOGGER.info("[PluginLoader] 🔗 Resolving plugin dependencies...");
            List<PluginContainer> sorted;
            try {
                sorted = DependencyResolver.sort(discovered);
                LOGGER.info("[PluginLoader] ✅ Dependencies resolved for {} plugins", sorted.size());
            } catch (Exception e) {
                LOGGER.error("[PluginLoader] ❌ Failed to resolve plugin dependencies", e);
                loadingErrors.add("Dependency resolution failed: " + e.getMessage());
                printPluginLoadingSummary();
                return;
            }
            
            // Load plugins in dependency order
            LOGGER.info("[PluginLoader] 🚀 Loading {} plugins in dependency order...", sorted.size());
            for (PluginContainer pc : sorted) {
                LOGGER.info("[PluginLoader] 📋 Loading plugin: {} ({})", 
                    pc.getDescription().name, pc.getDescription().id);
                
                try {
                    Plugin plugin = (Plugin) pc.getClassLoader()
                        .loadClass(pc.getDescription().mainClass)
                        .getDeclaredConstructor()
                        .newInstance();

                    PluginDescription description = pc.getDescription();
                    URL jarURL = pc.getClassLoader().getURLs()[0];
                    Path jarPath = Paths.get(jarURL.toURI());
                    PluginClassLoader loader = pc.getClassLoader();

                    PluginContextImpl context = new PluginContextImpl(description, manager.getProjectRoot(), plugin);
                    PluginRuntime runtime = new PluginRuntime(plugin, context, loader, jarPath);

                    runtime.enable();
                    pluginsLoaded++;
                    LOGGER.info("[PluginLoader] ✅ Plugin activated: {} v{}", 
                        description.name, description.version);
                    
                    pc.setInstance(plugin);
                    manager.register(pc.getInstance());
                    
                    // Mark plugin as ready for bridge synchronization
                    BridgePluginManager.markPluginReady(description.id);
                    LOGGER.info("[PluginLoader] 🌉 Bridge plugin marked ready: {}", description.id);
                    
                } catch (Exception e) {
                    pluginsFailed++;
                    String error = String.format("Failed to load plugin %s: %s", 
                        pc.getDescription().name, e.getMessage());
                    LOGGER.error("[PluginLoader] ❌ {}", error, e);
                    loadingErrors.add(error);
                    
                    // Try to disable if partially loaded
                    try {
                        if (pc.getInstance() != null) {
                            pc.getInstance().onDisable();
                        }
                    } catch (Exception disableError) {
                        LOGGER.warn("[PluginLoader] Failed to disable partially loaded plugin", disableError);
                    }
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("[PluginLoader] ❌ Critical error during plugin loading", e);
            loadingErrors.add("Critical loading error: " + e.getMessage());
        } finally {
            printPluginLoadingSummary();
            
            // Log bridge plugin status
            if (BridgePluginManager.isInitializationComplete()) {
                LOGGER.info("[PluginLoader] 🎉 All bridge plugins are ready for JavaScript!");
            } else {
                LOGGER.warn("[PluginLoader] ⚠ Bridge plugin initialization incomplete - {} plugins pending: {}", 
                           BridgePluginManager.getPendingPlugins().size(), 
                           BridgePluginManager.getPendingPlugins());
            }
        }
    }

    /**
     * Inspect a single plugin JAR file and build a {@link PluginContainer} for it.
     * <p>
     * This method:
     * <ul>
     *     <li>Checks for the presence of {@code plugin.yml} at the root of the JAR.</li>
     *     <li>Parses {@link PluginDescription} from {@code plugin.yml}.</li>
     *     <li>Validates {@code api-version} against {@link PluginApi#API_VERSION}.</li>
     *     <li>Creates an isolated {@link PluginClassLoader} for the plugin.</li>
     * </ul>
     *
     * @param jarPath the path to the plugin JAR.
     * @return a {@link PluginContainer} if the JAR is a valid plugin, or {@code null} if:
     * <ul>
     *     <li>{@code plugin.yml} is missing, or</li>
     *     <li>{@code api-version} is incompatible.</li>
     * </ul>
     * @throws IOException if the JAR cannot be opened or read.
     *
     * <p><b>Note:</b> This method does <u>not</u> handle assets anymore.
     * Asset extraction is performed separately in {@link #loadAssets()}.
     */
    private PluginContainer inspectJar(Path jarPath) throws IOException {
        LOGGER.debug("[PluginLoader] 🔍 Inspecting JAR: {}", jarPath.getFileName());
        
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Check for plugin.yml
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                LOGGER.warn("[PluginLoader] ❌ Missing plugin.yml in: {}", jarPath.getFileName());
                
                // Check if it has extension.json (common mistake)
                JarEntry extensionEntry = jar.getJarEntry("extension.json");
                if (extensionEntry != null) {
                    LOGGER.warn("[PluginLoader] ⚠ Found extension.json - this JAR is being treated as a JavaScript extension, not a Java plugin");
                    LOGGER.warn("[PluginLoader] 💡 Remove extension.json and ensure plugin.yml is properly formatted");
                } else {
                    LOGGER.info("[PluginLoader] 💡 Plugin JARs must contain plugin.yml at the root level");
                }
                return null;
            }

            // Parse plugin.yml
            PluginDescription desc;
            try (InputStream in = jar.getInputStream(entry)) {
                desc = new PluginDescription(in);
                LOGGER.debug("[PluginLoader] 📋 Parsed plugin description: {} v{} (ID: {})", 
                    desc.name, desc.version, desc.id);
            } catch (Exception e) {
                LOGGER.error("[PluginLoader] ❌ Failed to parse plugin.yml in {}: {}", 
                    jarPath.getFileName(), e.getMessage());
                LOGGER.info("[PluginLoader] 💡 Check plugin.yml format: name, id, main-class, version, api-version");
                return null;
            }

            // Validate required fields
            List<String> missingFields = new ArrayList<>();
            if (desc.name == null || desc.name.trim().isEmpty()) missingFields.add("name");
            if (desc.id == null || desc.id.trim().isEmpty()) missingFields.add("id");
            if (desc.mainClass == null || desc.mainClass.trim().isEmpty()) missingFields.add("main-class");
            if (desc.version == null || desc.version.trim().isEmpty()) missingFields.add("version");
            
            if (!missingFields.isEmpty()) {
                LOGGER.error("[PluginLoader] ❌ Missing required fields in {}: {}", 
                    jarPath.getFileName(), String.join(", ", missingFields));
                LOGGER.info("[PluginLoader] 💡 Required plugin.yml fields: name, id, main-class, version, api-version");
                return null;
            }

            // API compatibility check
            if (!PluginApi.API_VERSION.equals(desc.apiVersion)) {
                LOGGER.warn("[PluginLoader] ⚠ API version mismatch in {}: plugin has {}, core requires {}", 
                    jarPath.getFileName(), desc.apiVersion, PluginApi.API_VERSION);
                LOGGER.info("[PluginLoader] 💡 Update api-version in plugin.yml to: \"{}\"", PluginApi.API_VERSION);
                return null;
            }

            // Check if main class exists
            try {
                URL jarUrl = jarPath.toUri().toURL();
                PluginClassLoader cl = new PluginClassLoader(jarUrl, getClass().getClassLoader());
                
                // Try to load the main class to verify it exists
                Class<?> mainClass = cl.loadClass(desc.mainClass);
                if (!com.moud.plugin.api.Plugin.class.isAssignableFrom(mainClass)) {
                    LOGGER.error("[PluginLoader] ❌ Main class {} in {} does not extend Plugin", 
                        desc.mainClass, jarPath.getFileName());
                    LOGGER.info("[PluginLoader] 💡 Main class must extend com.moud.plugin.api.Plugin");
                    return null;
                }
                
                LOGGER.debug("[PluginLoader] ✅ Main class {} verified", desc.mainClass);
                return new PluginContainer(desc, cl);
                
            } catch (ClassNotFoundException e) {
                LOGGER.error("[PluginLoader] ❌ Main class {} not found in {}: {}", 
                    desc.mainClass, jarPath.getFileName(), e.getMessage());
                LOGGER.info("[PluginLoader] 💡 Check main-class path and ensure class is in the JAR");
                return null;
            } catch (NoClassDefFoundError e) {
                LOGGER.error("[PluginLoader] ❌ Missing dependency for {} in {}: {}", 
                    desc.mainClass, jarPath.getFileName(), e.getMessage());
                LOGGER.info("[PluginLoader] 💡 Ensure all dependencies are included in the plugin JAR");
                return null;
            } catch (Exception e) {
                LOGGER.error("[PluginLoader] ❌ Failed to create class loader for {}: {}", 
                    jarPath.getFileName(), e.getMessage());
                return null;
            }
            
        } catch (IOException e) {
            LOGGER.error("[PluginLoader] ❌ Failed to read JAR file {}: {}", 
                jarPath.getFileName(), e.getMessage());
            if (e.getMessage().contains("ZIP")) {
                LOGGER.info("[PluginLoader] 💡 The file may be corrupted or not a valid JAR");
            }
            throw e;
        }
    }
    
    /**
     * Prints a comprehensive summary of the plugin loading process.
     */
    private void printPluginLoadingSummary() {
        LOGGER.info("[PluginLoader] =======================================");
        LOGGER.info("[PluginLoader] Plugin Loading Summary");
        LOGGER.info("[PluginLoader] =======================================");
        LOGGER.info("[PluginLoader] 📊 Statistics:");
        LOGGER.info("[PluginLoader]   • Total JAR files scanned: {}", totalJarsScanned);
        LOGGER.info("[PluginLoader]   • Valid plugins detected: {}", pluginsDetected);
        LOGGER.info("[PluginLoader]   • Plugins successfully loaded: {}", pluginsLoaded);
        LOGGER.info("[PluginLoader]   • Plugins failed to load: {}", pluginsFailed);
        LOGGER.info("[PluginLoader]   • Files skipped (not plugins): {}", skippedFiles.size());
        
        if (pluginsLoaded > 0) {
            LOGGER.info("[PluginLoader] ✅ Plugin loading completed successfully!");
        } else if (pluginsDetected > 0) {
            LOGGER.warn("[PluginLoader] ⚠ Plugins were detected but none loaded successfully");
        } else {
            LOGGER.warn("[PluginLoader] ⚠ No valid plugins were found");
        }
        
        // Show skipped files
        if (!skippedFiles.isEmpty()) {
            LOGGER.info("[PluginLoader] 📋 Skipped files (not valid plugins):");
            for (String file : skippedFiles) {
                LOGGER.info("[PluginLoader]   • {}", file);
            }
        }
        
        // Show errors
        if (!loadingErrors.isEmpty()) {
            LOGGER.error("[PluginLoader] ❌ Errors encountered:");
            for (int i = 0; i < loadingErrors.size(); i++) {
                LOGGER.error("[PluginLoader]   {}. {}", i + 1, loadingErrors.get(i));
            }
        }
        
        // Provide helpful hints
        if (pluginsLoaded == 0 && totalJarsScanned > 0) {
            LOGGER.info("[PluginLoader] 💡 Troubleshooting Tips:");
            LOGGER.info("[PluginLoader]   • Ensure JAR files contain plugin.yml at the root level");
            LOGGER.info("[PluginLoader]   • Check that plugin.yml has correct format: name, id, main-class, version, api-version");
            LOGGER.info("[PluginLoader]   • Verify api-version matches: \"{}\"", PluginApi.API_VERSION);
            LOGGER.info("[PluginLoader]   • Make sure main class extends com.moud.plugin.api.Plugin");
            LOGGER.info("[PluginLoader]   • Remove extension.json files (they're for JavaScript extensions)");
            LOGGER.info("[PluginLoader]   • Check the test-plugin directory for a working example");
        }
        
        LOGGER.info("[PluginLoader] =======================================");
    }
}
