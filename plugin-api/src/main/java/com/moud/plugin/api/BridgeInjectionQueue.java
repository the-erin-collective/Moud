package com.moud.plugin.api;

import org.graalvm.polyglot.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A robust queue-based system for JavaScript bridge injection.
 * Ensures bridge objects are only injected after the GraalJS context is definitely ready.
 */
public class BridgeInjectionQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeInjectionQueue.class);

    private static final Queue<Runnable> injectionQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean contextReady = new AtomicBoolean(false);
    private static volatile Context jsContext;

    /**
     * Call this once the JavaScript Context is known to be ready.
     * This should be called from the JavaScript runtime when initialization is complete.
     */
    public static void notifyContextReady(Context context) {
        if (contextReady.compareAndSet(false, true)) {
            jsContext = context;
            LOGGER.info("[BridgeInjectionQueue] JS context is ready — executing {} queued injections", injectionQueue.size());
            
            while (!injectionQueue.isEmpty()) {
                Runnable task = injectionQueue.poll();
                try {
                    context.enter();
                    task.run();
                } catch (Throwable t) {
                    LOGGER.error("❌ Error executing queued bridge injection: {}", t.getMessage(), t);
                } finally {
                    context.leave();
                }
            }
            
            LOGGER.info("[BridgeInjectionQueue] All queued injections completed");
        } else {
            LOGGER.warn("[BridgeInjectionQueue] Context already marked as ready, ignoring duplicate notification");
        }
    }

    /**
     * Use this in plugins to defer JS injection until context is ready.
     * This is the primary method for plugins to safely inject bridge objects.
     */
    public static void queue(Runnable injection) {
        if (contextReady.get()) {
            LOGGER.debug("[BridgeInjectionQueue] Context ready, executing injection immediately");
            try {
                if (jsContext != null) {
                    jsContext.enter();
                    injection.run();
                } else {
                    LOGGER.warn("[BridgeInjectionQueue] Context marked ready but jsContext is null, queueing injection");
                    injectionQueue.offer(injection);
                }
            } catch (Throwable t) {
                LOGGER.error("❌ Error executing immediate bridge injection: {}", t.getMessage(), t);
            } finally {
                if (jsContext != null) {
                    jsContext.leave();
                }
            }
        } else {
            LOGGER.debug("[BridgeInjectionQueue] Bridge context not ready — queueing injection");
            injectionQueue.offer(injection);
        }
    }

    /**
     * Check if the JavaScript context is ready for injections.
     */
    public static boolean isReady() {
        return contextReady.get();
    }

    /**
     * Get the number of pending injections in the queue.
     */
    public static int getPendingCount() {
        return injectionQueue.size();
    }

    /**
     * Reset the queue state (for testing or server restart scenarios).
     */
    static void reset() {
        contextReady.set(false);
        injectionQueue.clear();
        jsContext = null;
        LOGGER.info("[BridgeInjectionQueue] Reset completed");
    }
}
