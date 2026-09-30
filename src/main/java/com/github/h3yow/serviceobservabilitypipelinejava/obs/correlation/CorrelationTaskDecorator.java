package com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the correlation id across thread-pool boundaries.
 *
 * <p>The id is captured at <em>submission time</em> on the caller's thread (inside
 * {@link #decorate}), and re-bound on the worker thread only for the duration of the
 * task. When no context exists at submission (e.g. a self-triggered scheduled job),
 * a deterministic {@code auto-} fallback is generated so background work is never
 * anonymous. The worker thread's previous binding is restored after the task, which
 * keeps pooled threads from leaking one request's id into the next.
 */
public class CorrelationTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(CorrelationTaskDecorator.class);

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture happens on the submitting thread.
        String capturedId = CorrelationContext.current();
        boolean generated = capturedId == null;
        if (generated) {
            capturedId = CorrelationIds.generateFallback();
        }
        String effectiveId = capturedId;
        return () -> {
            String previous = CorrelationContext.current();
            try {
                CorrelationContext.begin(effectiveId);
                if (generated) {
                    log.info("No propagated correlation id; generated fallback id={}", effectiveId);
                }
                runnable.run();
            } finally {
                // Restore whatever the pooled thread had before (usually null).
                if (previous == null) {
                    CorrelationContext.end();
                } else {
                    CorrelationContext.begin(previous);
                }
            }
        };
    }
}
