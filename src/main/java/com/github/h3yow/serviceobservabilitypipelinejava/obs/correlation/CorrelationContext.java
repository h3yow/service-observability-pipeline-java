package com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation;

import org.slf4j.MDC;

/**
 * Thread-local holder for the per-request correlation id, backed by SLF4J MDC so
 * every log line emitted on the executing thread carries it automatically.
 *
 * <p>Typical lifecycle for an inbound request:
 * <pre>{@code
 * String id = CorrelationContext.begin(acceptedOrFallbackId);
 * try {
 *     filterChain.doFilter(...);
 * } finally {
 *     CorrelationContext.end();
 * }
 * }</pre>
 *
 * <p>For thread hops the value is captured on the submitting thread and re-established
 * via {@link #runWith} on the worker thread; the worker's previous context (possibly
 * from a pooled thread's earlier task) is always restored afterwards, so two
 * concurrent requests can never borrow each other's id.
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
    }

    /**
     * Binds the given id to the current thread and MDC.
     *
     * @return the bound id
     */
    public static String begin(String correlationId) {
        CURRENT.set(correlationId);
        MDC.put(MDC_KEY, correlationId);
        return correlationId;
    }

    /**
     * @return the id bound to the current thread, or {@code null} when none is bound
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Removes any binding from the current thread and MDC. Always call in finally.
     */
    public static void end() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /**
     * Runs {@code task} with {@code correlationId} bound, restoring the previous
     * binding afterwards. A {@code null} id means "no context": the task runs without
     * a binding and the previous state is still restored.
     */
    public static void runWith(String correlationId, Runnable task) {
        String previous = CURRENT.get();
        try {
            if (correlationId == null) {
                CURRENT.remove();
                MDC.remove(MDC_KEY);
            } else {
                CURRENT.set(correlationId);
                MDC.put(MDC_KEY, correlationId);
            }
            task.run();
        } finally {
            if (previous == null) {
                end();
            } else {
                begin(previous);
            }
        }
    }

    /**
     * Runs {@code task} under a freshly generated fallback id.
     *
     * @return the generated id
     */
    public static String runWithGenerated(Runnable task) {
        String id = CorrelationIds.generateFallback();
        runWith(id, task);
        return id;
    }
}
