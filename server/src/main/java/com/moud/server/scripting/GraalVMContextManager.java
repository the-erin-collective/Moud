package com.moud.server.scripting;

import com.moud.server.logging.MoudLogger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GraalVM Context Manager for thread-safe JavaScript execution.
 * 
 * This manager ensures that all JavaScript operations happen on a dedicated
 * thread, preventing GraalVM context violations and providing a safe API
 * for other components to interact with the JavaScript runtime.
 */
public class GraalVMContextManager {
    private static final MoudLogger LOGGER = MoudLogger.getLogger(GraalVMContextManager.class);
    
    private final Thread jsThread;
    private final Context context;
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    public GraalVMContextManager() {
        LOGGER.info("[GraalVMContextManager] Initializing GraalVM Context Manager...");
        
        // Create the GraalVM context with proper permissions
        this.context = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(s -> true)
                .allowIO(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        
        // Create and start the dedicated JavaScript thread
        this.jsThread = new Thread(this::runLoop, "GraalJS-Context-Thread");
        this.jsThread.start();
        
        LOGGER.info("[GraalVMContextManager] Context Manager initialized with thread: {}", jsThread.getId());
    }
    
    /**
     * Main execution loop for the JavaScript thread.
     * Processes tasks from the queue and executes them within the GraalVM context.
     */
    private void runLoop() {
        LOGGER.info("[GraalVMContextManager] JavaScript thread {} started", Thread.currentThread().getId());
        
        while (running.get()) {
            try {
                // Wait for a task
                Runnable task = taskQueue.take();
                
                // Execute within the GraalVM context
                context.enter();
                try {
                    task.run();
                } finally {
                    context.leave();
                }
                
            } catch (InterruptedException e) {
                LOGGER.info("[GraalVMContextManager] JavaScript thread interrupted, shutting down");
                break;
            } catch (Throwable t) {
                LOGGER.error("[GraalVMContextManager] Error executing JavaScript task", t);
            }
        }
        
        LOGGER.info("[GraalVMContextManager] JavaScript thread {} stopped", Thread.currentThread().getId());
    }
    
    /**
     * Submit a task to be executed on the JavaScript thread.
     * 
     * @param task The task to execute
     */
    public void submit(Runnable task) {
        if (!running.get()) {
            LOGGER.warn("[GraalVMContextManager] Attempted to submit task to shutdown context manager");
            return;
        }
        
        taskQueue.add(task);
        LOGGER.debug("[GraalVMContextManager] Task submitted to queue (size: {})", taskQueue.size());
    }
    
    /**
     * Submit a task that returns a value and get a CompletableFuture.
     * 
     * @param task The task to execute
     * @param <T> The return type
     * @return CompletableFuture containing the result
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        
        submit(() -> {
            try {
                T result = task.call();
                future.complete(result);
            } catch (Exception e) {
                LOGGER.error("[GraalVMContextManager] Error executing callable task", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Evaluate JavaScript code safely on the JavaScript thread.
     * 
     * @param code The JavaScript code to evaluate
     * @return CompletableFuture containing the result
     */
    public CompletableFuture<Value> eval(String code) {
        return submit(() -> context.eval("js", code));
    }
    
    /**
     * Check if the current thread is the JavaScript thread.
     * 
     * @return true if current thread is the JavaScript thread
     */
    public boolean isOnJsThread() {
        return Thread.currentThread() == jsThread;
    }
    
    /**
     * Get the JavaScript thread ID for logging purposes.
     * 
     * @return The thread ID
     */
    public long getJsThreadId() {
        return jsThread.getId();
    }
    
    /**
     * Get the GraalVM context (for internal use only).
     * 
     * @return The GraalVM context
     */
    public Context getContext() {
        return context;
    }
    
    /**
     * Check if the context manager is running.
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get the current task queue size.
     * 
     * @return Number of pending tasks
     */
    public int getQueueSize() {
        return taskQueue.size();
    }
    
    /**
     * Shutdown the context manager gracefully.
     */
    public void shutdown() {
        LOGGER.info("[GraalVMContextManager] Shutting down GraalVM Context Manager...");
        
        running.set(false);
        jsThread.interrupt();
        
        try {
            jsThread.join(5000); // Wait up to 5 seconds for thread to finish
            if (jsThread.isAlive()) {
                LOGGER.warn("[GraalVMContextManager] JavaScript thread did not shutdown gracefully");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("[GraalVMContextManager] Interrupted while waiting for JavaScript thread shutdown");
        }
        
        context.close();
        LOGGER.info("[GraalVMContextManager] Context Manager shutdown complete");
    }
}
