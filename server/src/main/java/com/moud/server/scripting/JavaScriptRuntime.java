package com.moud.server.scripting;

import com.moud.server.MoudEngine;
import com.moud.server.ConsoleAPI;
import com.moud.server.api.ScriptingAPI;
import com.moud.server.logging.MoudLogger;
import com.moud.server.profiler.ProfilerService;
import com.moud.server.profiler.model.ScriptExecutionMetadata;
import com.moud.server.profiler.model.ScriptExecutionType;
import com.moud.server.profiler.script.ScriptProfiler;
import com.moud.server.typescript.TypeScriptTranspiler;
import com.moud.plugin.api.BridgeRegistry;
import com.moud.plugin.api.BridgePluginManager;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class JavaScriptRuntime {
    private static final MoudLogger LOGGER = MoudLogger.getLogger(JavaScriptRuntime.class);
    private static final long CALLBACK_TIMEOUT_MS = Long.getLong("moud.script.timeout", 30000);

    private final GraalVMContextManager contextManager;
    private final ScheduledExecutorService timeoutExecutor;
    private final MoudEngine engine;
    private volatile boolean isShuttingDown = false;
    private final Map<Long, ScheduledFuture<?>> intervals = new ConcurrentHashMap<>();
    private final AtomicLong intervalIdCounter = new AtomicLong(0);

    public JavaScriptRuntime(MoudEngine engine) {
        this.engine = engine;
        this.contextManager = new GraalVMContextManager();
        this.timeoutExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "JavaScriptRuntime-Timeout");
            t.setDaemon(true);
            return t;
        });
        
        LOGGER.info("[JavaScriptRuntime] Initializing JavaScript runtime with context manager...");
        initializeContext();
    }

    private void initializeContext() {
        LOGGER.info("[JavaScriptRuntime] Context manager initialized on thread: {}", contextManager.getJsThreadId());
        
        // Bind timer functions using the context manager
        bindTimerFunctions();
    }

    private void bindTimerFunctions() {
        contextManager.submit(() -> {
            try {
                contextManager.getContext().getBindings("js").putMember("setTimeout", new ProxyExecutable() {
                    @Override
                    public Object execute(Value... arguments) {
                        if (arguments.length < 2) return -1;
                        Value callback = arguments[0];
                        long delay = arguments[1].asLong();
                        ScriptExecutionMetadata metadata = ScriptExecutionMetadata.of(
                                ScriptExecutionType.TIMEOUT,
                                "setTimeout",
                                "delay=" + delay
                        );

                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, timeoutExecutor)
                                .execute(() -> executeCallbackSafe(callback, metadata));
                        return null;
                    }
                });

                contextManager.getContext().getBindings("js").putMember("setInterval", new ProxyExecutable() {
                    @Override
                    public Object execute(Value... arguments) {
                        if (arguments.length < 2) return -1;
                        Value callback = arguments[0];
                        long delay = arguments[1].asLong();
                        long intervalId = intervalIdCounter.incrementAndGet();
                        ScriptExecutionMetadata metadata = ScriptExecutionMetadata.of(
                                ScriptExecutionType.INTERVAL,
                                "setInterval",
                                "id=" + intervalId
                        );

                        ScheduledFuture<?> future = timeoutExecutor.scheduleAtFixedRate(() -> {
                            if (!isShuttingDown && contextManager.isRunning()) {
                                try {
                                    executeCallbackSafe(callback, metadata);
                                } catch (IllegalStateException e) {
                                    // context was closed during execute
                                    intervals.remove(intervalId);
                                }
                            }
                        }, delay, delay, TimeUnit.MILLISECONDS);

                        intervals.put(intervalId, future);
                        return intervalId;
                    }
                });

                contextManager.getContext().getBindings("js").putMember("clearInterval", new ProxyExecutable() {
                    @Override
                    public Object execute(Value... arguments) {
                        if (arguments.length < 1) return -1;
                        long intervalId = arguments[0].asLong();
                        ScheduledFuture<?> future = intervals.remove(intervalId);
                        if (future != null) {
                            future.cancel(false);
                        }
                        return null;
                    }
                });

                LOGGER.info("[JavaScriptRuntime] Timer functions bound successfully");
            } catch (Exception e) {
                LOGGER.error("[JavaScriptRuntime] Failed to bind timer functions", e);
            }
        });
    }

    public void bindModules(ScriptingAPI scriptingAPI, ConsoleAPI consoleAPI, Collection<MoudScriptModule> modules) {
        LOGGER.info("[JavaScriptRuntime] Waiting for bridge plugins to be ready before initializing JavaScript...");
        
        // Wait for all bridge plugins to be ready before initializing JavaScript
        BridgePluginManager.getReadinessFuture().thenRun(() -> {
            LOGGER.info("[JavaScriptRuntime] All bridge plugins ready - initializing JavaScript runtime...");
            
            // Execute on the context manager thread
            LOGGER.info("[JavaScriptRuntime] Submitting module binding to context manager thread {} from thread {}", 
                       contextManager.getJsThreadId(), Thread.currentThread().getId());
            
            // Submit to the context manager
            contextManager.submit(() -> {
                bindModulesOnContextThread(scriptingAPI, consoleAPI, modules);
            });
        }).exceptionally(throwable -> {
            LOGGER.error("[JavaScriptRuntime] Failed to wait for bridge plugins", throwable);
            return null;
        });
    }
    
    private void bindModulesOnContextThread(ScriptingAPI scriptingAPI, ConsoleAPI consoleAPI, Collection<MoudScriptModule> modules) {
        LOGGER.info("[JavaScriptRuntime] Starting module binding on context manager thread {}", 
                   Thread.currentThread().getId());
        
        try {
            // Use the context manager's context
            Value bindings = contextManager.getContext().getBindings("js");
            Value api = contextManager.getContext().eval("js", "({})");

            bindEventAPI(api, scriptingAPI);
            bindModules(api, modules);

            if (consoleAPI != null) {
                bindings.putMember("console", consoleAPI);
            }

            bindings.putMember("Moud", api);
            bindings.putMember("api", api);
            LOGGER.info("[JavaScriptRuntime] Injected Moud and api objects");
            
            // CRITICAL: Inject Java bridge access for resolveJavaBridge()
            try {
                LOGGER.info("[JavaScriptRuntime] Injecting Java bridge access...");
                Value javaBridge = contextManager.getContext().eval("js", "({})");
                
                // Inject Java.type access through GraalVM
                Value javaType = contextManager.getContext().eval("js", "Java.type");
                javaBridge.putMember("type", javaType);
                
                // Inject common Java classes using GraalVM's Java access
                javaBridge.putMember("System", contextManager.getContext().eval("js", "Java.type('java.lang.System')"));
                javaBridge.putMember("Class", contextManager.getContext().eval("js", "Java.type('java.lang.Class')"));
                javaBridge.putMember("String", contextManager.getContext().eval("js", "Java.type('java.lang.String')"));
                javaBridge.putMember("Object", contextManager.getContext().eval("js", "Java.type('java.lang.Object')"));
                
                // Make Java available globally
                bindings.putMember("Java", javaBridge);
                
                // Also inject into Moud and api objects for resolveJavaBridge()
                api.putMember("java", javaBridge);
                
                LOGGER.info("[JavaScriptRuntime] Java bridge injection completed");
            } catch (Exception e) {
                LOGGER.error("[JavaScriptRuntime] Failed to inject Java bridge: {}", e.getMessage(), e);
            }
            
            // Inject bridge services from BridgeRegistry
            injectBridgeServices(bindings);
            
            // Inject bridgeReady() function for JavaScript
            injectBridgeReadyFunction(bindings);

            LOGGER.info("[JavaScriptRuntime] JavaScript runtime initialization completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("[JavaScriptRuntime] ERROR in bindModules: {}", e.getMessage(), e);
        }
    }

    /**
     * Injects the bridgeReady() function into JavaScript global scope.
     * This function allows JavaScript code to await bridge plugin readiness.
     */
    private void injectBridgeReadyFunction(Value bindings) {
        try {
            bindings.putMember("bridgeReady", new ProxyExecutable() {
                @Override
                public Object execute(Value... arguments) {
                    // Since we're already here, all plugins are ready, so return a resolved promise
                    return contextManager.getContext().eval("js", "Promise.resolve()");
                }
            });
            
            // Also provide a synchronous check function
            bindings.putMember("areBridgesReady", new ProxyExecutable() {
                @Override
                public Object execute(Value... arguments) {
                    return BridgePluginManager.isInitializationComplete();
                }
            });
            
            // Provide bridge status information
            bindings.putMember("getBridgeStatus", new ProxyExecutable() {
                @Override
                public Object execute(Value... arguments) {
                    Value status = contextManager.getContext().eval("js", "({})");
                    status.putMember("ready", BridgePluginManager.isInitializationComplete());
                    status.putMember("loaded", BridgePluginManager.getLoadedPlugins());
                    status.putMember("expected", BridgePluginManager.getExpectedPlugins());
                    status.putMember("pending", BridgePluginManager.getPendingPlugins());
                    return status;
                }
            });
            
            LOGGER.info("Injected bridgeReady(), areBridgesReady(), and getBridgeStatus() functions into JavaScript");
        } catch (Exception e) {
            LOGGER.error("Failed to inject bridge ready functions into JavaScript", e);
        }
    }

    /**
     * Injects all registered bridge services into the JavaScript global scope.
     * Each registered service becomes a global variable accessible from JavaScript.
     */
    private void injectBridgeServices(Value bindings) {
        Map<String, Object> bridgeServices = BridgeRegistry.getAllServices();
        
        for (Map.Entry<String, Object> entry : bridgeServices.entrySet()) {
            String serviceId = entry.getKey();
            Object service = entry.getValue();
            
            try {
                bindings.putMember(serviceId, service);
                LOGGER.info("Injected bridge service '{}' into JavaScript global scope", serviceId);
            } catch (Exception e) {
                LOGGER.error("Failed to inject bridge service '{}' into JavaScript", serviceId, e);
            }
        }
        
        if (!bridgeServices.isEmpty()) {
            LOGGER.info("Successfully injected {} bridge services into JavaScript runtime", bridgeServices.size());
        }
    }

    public CompletableFuture<Void> registerGlobal(String name, Object value) {
        if (name == null || name.isBlank() || value == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        contextManager.submit(() -> {
            try {
                contextManager.getContext().getBindings("js").putMember(name, value);
                LOGGER.info("Registered external JS global: {}", name);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void bindEventAPI(Value apiObject, ScriptingAPI scriptingAPI) {
        if (apiObject == null || scriptingAPI == null) {
            return;
        }

        apiObject.putMember("on", new ProxyExecutable() {
            @Override
            public Object execute(Value... arguments) {
                if (arguments.length >= 2) {
                    scriptingAPI.on(arguments[0].asString(), arguments[1]);
                }
                return null;
            }
        });

        apiObject.putMember("once", new ProxyExecutable() {
            @Override
            public Object execute(Value... arguments) {
                if (arguments.length >= 2) {
                    scriptingAPI.once(arguments[0].asString(), arguments[1]);
                }
                return null;
            }
        });

        apiObject.putMember("off", new ProxyExecutable() {
            @Override
            public Object execute(Value... arguments) {
                if (arguments.length >= 2) {
                    scriptingAPI.off(arguments[0].asString(), arguments[1]);
                }
                return null;
            }
        });
    }

    private void bindModules(Value apiObject, Collection<MoudScriptModule> modules) {
        if (apiObject == null || modules == null || modules.isEmpty()) {
            return;
        }

        for (MoudScriptModule module : modules) {
            if (module == null) {
                continue;
            }

            String namespace = module.getNamespace();
            if (namespace == null || namespace.isBlank()) {
                continue;
            }

            Object proxy;
            try {
                proxy = module.getProxy();
            } catch (Exception e) {
                LOGGER.warn("Failed to resolve script module '{}' proxy", namespace, e);
                continue;
            }
            if (proxy == null) {
                continue;
            }

            apiObject.putMember(namespace, proxy);
        }
    }

    public CompletableFuture<Void> executeScript(Path scriptPath) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        contextManager.submit(() -> {
            try {
                if (isShuttingDown) {
                    future.complete(null);
                    return;
                }

                String scriptContent;
                String fileName = scriptPath.getFileName().toString();

                if (fileName.endsWith(".ts")) {
                    scriptContent = TypeScriptTranspiler.transpile(scriptPath).get();
                } else {
                    scriptContent = Files.readString(scriptPath);
                }

                evaluateSource(scriptContent, fileName);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> executeSource(String scriptContent, String virtualFileName) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        contextManager.submit(() -> {
            try {
                evaluateSource(scriptContent, virtualFileName);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void evaluateSource(String scriptContent, String virtualFileName) {
        if (isShuttingDown) {
            return;
        }

        try {
            Source source = Source.newBuilder("js", scriptContent, virtualFileName).buildLiteral();
            contextManager.getContext().eval(source);
        } catch (PolyglotException e) {
            if (e.isGuestException() && e.getSourceLocation() != null) {
                LOGGER.scriptError("Execution failed in {} at line {}, column {}",
                        virtualFileName,
                        e.getSourceLocation().getStartLine(),
                        e.getSourceLocation().getStartColumn(),
                        e.getMessage());
                LOGGER.error("└─> {}", e.getMessage());
            } else {
                LOGGER.error("Host error during script execution", e);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to evaluate script source {}", virtualFileName, e);
        }
    }

    public void executeCallback(Value callback, Object... args) {
        executeCallback(callback, ScriptExecutionMetadata.unnamed(ScriptExecutionType.OTHER), args);
    }

    public void executeCallback(Value callback, ScriptExecutionMetadata metadata, Object... args) {
        if (isShuttingDown) {
            return;
        }
        contextManager.submit(() -> runCallback(callback, metadata, args));
    }

    private void executeCallbackSafe(Value callback) {
        executeCallbackSafe(callback, ScriptExecutionMetadata.unnamed(ScriptExecutionType.TIMEOUT));
    }

    private void executeCallbackSafe(Value callback, ScriptExecutionMetadata metadata, Object... args) {
        if (isShuttingDown) return;

        CompletableFuture.runAsync(() -> runCallback(callback, metadata, args), timeoutExecutor);

        timeoutExecutor.schedule(() -> {
            LOGGER.error("Callback execution timed out after {}ms [{}]", CALLBACK_TIMEOUT_MS, metadata.label());
        }, CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void runCallback(Value callback, ScriptExecutionMetadata metadata, Object... args) {
        if (callback == null || !callback.canExecute()) {
            return;
        }

        ScriptProfiler.ActiveSpan span = ProfilerService.getInstance()
                .scriptProfiler()
                .open(callback, metadata);
        long start = System.nanoTime();

        boolean success = false;
        String errorMessage = null;

        try {
            callback.execute(args);
            success = true;
        } catch (PolyglotException e) {
            errorMessage = e.getMessage();
            handlePolyglotException(e);
        } catch (CancellationException e) {
            errorMessage = "Cancelled";
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                errorMessage = "Interrupted";
            } else {
                errorMessage = e.getMessage();
                LOGGER.error("Unexpected error in callback execution", e);
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
            LOGGER.error("Unexpected error in callback execution", e);
        } finally {
            ProfilerService.getInstance().scriptProfiler()
                    .close(span, System.nanoTime() - start, success, errorMessage);
        }
    }

    private void handlePolyglotException(PolyglotException e) {
        if (e.isGuestException() && e.getSourceLocation() != null) {
            LOGGER.scriptError("Error in callback execution at {} (line {}, column {}): {}",
                    e.getSourceLocation().getSource().getName(),
                    e.getSourceLocation().getStartLine(),
                    e.getSourceLocation().getStartColumn(),
                    e.getMessage());
        } else {
            LOGGER.error("Error executing callback: {}", e.getMessage());
        }
    }

    public ExecutorService getExecutor() {
        return timeoutExecutor;
    }

    public Context getContext() {
        return contextManager.getContext();
    }

    public CompletableFuture<Value> eval(String language, String code) {
        return contextManager.eval(code);
    }

    public void shutdown() {
        isShuttingDown = true;

        LOGGER.debug("Cancelling {} active intervals before context shutdown", intervals.size());
        intervals.values().forEach(future -> future.cancel(true)); // Use true for immediate interrupt
        intervals.clear();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown the context manager
        contextManager.shutdown();

        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
