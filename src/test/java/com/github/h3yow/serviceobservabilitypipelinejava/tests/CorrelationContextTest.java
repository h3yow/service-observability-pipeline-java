package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationIds;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationTaskDecorator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests for context binding and propagation across thread pools.
 */
class CorrelationContextTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationContextTest.class);

    @Test
    @DisplayName("Concurrent runWith blocks never see another request's id, and always restore state")
    void concurrentBindingsDoNotBleed() throws Exception {
        int threads = 32;
        int iterations = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];

        for (int t = 0; t < threads; t++) {
            final String requestId = "req-" + t;
            futures[t] = pool.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    final boolean[] observedMatch = {false};
                    CorrelationContext.runWith(requestId, () -> {
                        String seen = CorrelationContext.current();
                        observedMatch[0] = requestId.equals(seen)
                                && requestId.equals(MDC.get(CorrelationContext.MDC_KEY));
                    });
                    if (!observedMatch[0]) {
                        mismatches.incrementAndGet();
                    }
                    // After runWith the pooled thread must be clean (or restored).
                    if (CorrelationContext.current() != null) {
                        mismatches.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        int checks = threads * iterations;
        log.info("input: threads={} iterations={} totalChecks={}", threads, iterations, checks);
        log.info("judgement: mismatches={} (expect 0), postTaskLeakChecks={}", mismatches.get(), checks);
        assertEquals(0, mismatches.get(), "context must not bleed across concurrent requests");
    }

    @Test
    @DisplayName("TaskDecorator captures the submitter id; a submitter without context gets an auto- id")
    void taskDecoratorPropagatesAndFallsBack() throws Exception {
        CorrelationTaskDecorator decorator = new CorrelationTaskDecorator();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // 1. propagation from request thread
            // 1. propagation: decoration happens on the submitting thread (as the
            //    real executor does inside submit), execution happens on the worker.
            String propagated = "upstream-id-42";
            String[] holder1 = new String[1];
            CorrelationContext.begin(propagated);
            Runnable decorated1;
            try {
                decorated1 = decorator.decorate(() -> holder1[0] = CorrelationContext.current());
            } finally {
                CorrelationContext.end();
            }
            pool.submit(decorated1).get();
            String seen = holder1[0];
            assertEquals(propagated, seen);
            log.info("input: submitterId={} judgement: workerSaw={} -> PROPAGATED",
                    propagated, seen);

            // 2. fallback when nothing is bound at submission. Decorate off-thread
            //    context (the current test thread has none after end()).
            String[] holder2 = new String[1];
            Runnable decorated2 = decorator.decorate(() -> holder2[0] = CorrelationContext.current());
            pool.submit(decorated2).get();
            String generated = holder2[0];
            assertTrue(generated.startsWith(CorrelationIds.FALLBACK_PREFIX),
                    "context-less submission must receive an auto- fallback");
            log.info("input: submitterId=<none> judgement: workerSaw={} -> FALLBACK GENERATED", generated);

            // 3. worker threads must be clean after the decorated task finishes
            String after = pool.submit(CorrelationContext::current).get();
            log.info("input: worker reused after task judgement: currentAfterTask={} (expect null)", after);
            assertNull(after, "decorated task must not leak context onto the pooled thread");
        } finally {
            pool.shutdown();
        }
    }
}
