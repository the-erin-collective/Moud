package com.moud.server.typescript;

import com.moud.server.project.ProjectLoader;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public final class TypeScriptTranspiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TypeScriptTranspiler.class);
    private static final int TIMEOUT_SECONDS = 30;

    private TypeScriptTranspiler() {
    }

    public static CompletableFuture<String> transpile(Path tsFile) {
        return transpile(tsFile, false);
    }

    public static CompletableFuture<String> transpile(Path tsFile, boolean isClientScript) {
        return transpile(tsFile, isClientScript ? BundleFormat.CLIENT_IIFE : BundleFormat.SERVER_ESM);
    }

    public static CompletableFuture<String> transpileSharedPhysics(Path tsFile) {
        return transpile(tsFile, BundleFormat.SHARED_PHYSICS_CJS);
    }

    private static CompletableFuture<String> transpile(Path tsFile, BundleFormat bundleFormat) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(tsFile)) {
                    throw new IllegalArgumentException("TypeScript file not found: " + tsFile);
                }

                String npxPath = findNpxExecutable();
                if (npxPath != null) {
                    return transpileWithEsbuild(tsFile, npxPath, bundleFormat);
                }

                Path cachedBundle = resolveCachedBundle(bundleFormat);
                if (cachedBundle != null && Files.exists(cachedBundle)) {
                    LOGGER.info("Using cached server bundle from {}", cachedBundle);
                    return Files.readString(cachedBundle, StandardCharsets.UTF_8);
                }

                throw new IllegalStateException(
                        "Unable to locate npx/esbuild and no cached bundle was found. " +
                        "Install Node.js (>=18) so the CLI can transpile, or run `moud dev` to generate cached artifacts."
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to transpile TypeScript", e);
            }
        });
    }

    private static Path resolveCachedBundle(BundleFormat bundleFormat) {
        try {
            Path projectRoot = ProjectLoader.findProjectRoot();
            Path bundle = projectRoot.resolve(".moud/cache/" + bundleFormat.cacheFileName);
            if (Files.exists(bundle)) {
                return bundle;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String findNpxExecutable() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        LOGGER.info("Finding npx executable on Windows: {}", isWindows);
        
        // TEMPORARY FIX: Hardcode npx.cmd path for Windows testing
        if (isWindows) {
            String hardcodedPath = "C:\\Program Files\\nodejs\\npx.cmd";
            File npxCmd = new File(hardcodedPath);
            if (npxCmd.exists()) {
                LOGGER.info("Using hardcoded npx.cmd path: {}", hardcodedPath);
                return hardcodedPath;
            }
        }
        
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null || pathEnv.isEmpty()) {
            LOGGER.warn("PATH environment variable is null or empty");
            return null;
        }

        String separator = File.pathSeparator;
        String[] pathDirs = pathEnv.split(separator);
        LOGGER.info("Checking {} directories in PATH", pathDirs.length);

        // On Windows, explicitly check for .cmd and .exe files first
        if (isWindows) {
            LOGGER.info("Windows detected, checking for npx.cmd and npx.exe first");
            for (String dir : pathDirs) {
                // Check npx.cmd first
                File npxCmd = new File(dir, "npx.cmd");
                if (npxCmd.exists()) {
                    LOGGER.info("Found npx.cmd at {}", npxCmd.getAbsolutePath());
                    return npxCmd.getAbsolutePath();
                }
                
                // Then check npx.exe
                File npxExe = new File(dir, "npx.exe");
                if (npxExe.exists()) {
                    LOGGER.info("Found npx.exe at {}", npxExe.getAbsolutePath());
                    return npxExe.getAbsolutePath();
                }
            }
            LOGGER.warn("No npx.cmd or npx.exe found in PATH");
        }

        // Fallback to checking all variants
        String[] possibleCommands = isWindows
                ? new String[]{"npx.cmd", "npx.exe", "npx"}
                : new String[]{"npx", "npx.cmd", "npx.exe"};

        LOGGER.info("Fallback: checking for commands {}", Arrays.toString(possibleCommands));

        for (String dir : pathDirs) {
            for (String cmd : possibleCommands) {
                File executable = new File(dir, cmd);
                if (executable.exists()) {
                    // On Windows, NEVER allow plain "npx" - it's a Unix script
                    if (isWindows && "npx".equals(cmd)) {
                        LOGGER.warn("Skipping Unix npx script on Windows: {}", executable.getAbsolutePath());
                        continue;
                    }
                    boolean isExecutable = !isWindows || executable.canExecute() || cmd.endsWith(".cmd") || cmd.endsWith(".exe");
                    if (isExecutable) {
                        LOGGER.info("FIXED VERSION - Found npx at {}", executable.getAbsolutePath());
                        return executable.getAbsolutePath();
                    }
                }
            }
        }

        LOGGER.warn("No npx executable found in PATH");
        return null;
    }

    private static String transpileWithEsbuild(Path tsFile, String npxPath, BundleFormat bundleFormat) throws Exception {
        Path projectRoot = ProjectLoader.findProjectRoot();
        Path tempDir = Files.createTempDirectory("moud-ts");
        Path jsFile = tempDir.resolve(tsFile.getFileName().toString().replaceFirst("\\.tsx?$", ".js"));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            CommandLine cmdLine = new CommandLine(npxPath);
            cmdLine.addArgument("esbuild");
            cmdLine.addArgument(tsFile.toAbsolutePath().toString(), true);
            cmdLine.addArgument("--outfile=" + jsFile.toAbsolutePath(), true);
            cmdLine.addArgument("--bundle");
            cmdLine.addArgument("--target=es2020");
            cmdLine.addArgument("--format=" + bundleFormat.esbuildFormat);
            cmdLine.addArgument("--platform=" + bundleFormat.esbuildPlatform);
            
            // Add external Node.js built-in modules to prevent bundling issues
            String[] nodeBuiltins = {
                "crypto", "fs", "path", "os", "util", "events", "stream", 
                "buffer", "child_process", "cluster", "dgram", "dns", "http", 
                "https", "net", "readline", "repl", "tls", "url", "zlib"
            };
            for (String builtin : nodeBuiltins) {
                cmdLine.addArgument("--external:" + builtin);
            }

            DefaultExecutor executor = DefaultExecutor.builder().get();
            executor.setWorkingDirectory(projectRoot.toFile());
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));

            ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
                    .setTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .get();
            executor.setWatchdog(watchdog);

            int exitCode = executor.execute(cmdLine);
            if (exitCode != 0) {
                String error = stderr.toString(StandardCharsets.UTF_8);
                LOGGER.error("esbuild failed with exit code {}: {}", exitCode, error);
                Path cachedBundle = resolveCachedBundle(bundleFormat);
                if (cachedBundle != null && Files.exists(cachedBundle)) {
                    LOGGER.warn("Falling back to cached bundle {}", cachedBundle);
                    return Files.readString(cachedBundle, StandardCharsets.UTF_8);
                }
                throw new IllegalStateException("esbuild failed and no cached bundle is available");
            }

            return Files.readString(jsFile, StandardCharsets.UTF_8);
        } finally {
            try {
                Files.deleteIfExists(jsFile);
                Files.deleteIfExists(tempDir);
            } catch (IOException cleanupError) {
                LOGGER.debug("Failed to clean up temporary transpilation artifacts", cleanupError);
            }
        }
    }

    private enum BundleFormat {
        SERVER_ESM("esm", "node", "server.bundle.js"),
        CLIENT_IIFE("iife", "browser", "client.bundle.js"),
        SHARED_PHYSICS_CJS("cjs", "neutral", "shared.bundle.js");

        private final String esbuildFormat;
        private final String esbuildPlatform;
        private final String cacheFileName;

        BundleFormat(String esbuildFormat, String esbuildPlatform, String cacheFileName) {
            this.esbuildFormat = esbuildFormat;
            this.esbuildPlatform = esbuildPlatform;
            this.cacheFileName = cacheFileName;
        }
    }
}
