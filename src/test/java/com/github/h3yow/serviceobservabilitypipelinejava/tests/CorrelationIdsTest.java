package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for fallback-id generation and inbound-id sanitization.
 */
class CorrelationIdsTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdsTest.class);

    @Test
    @DisplayName("Fallback ids are unique under concurrency and distinguishable by prefix")
    void fallbackIdsAreUniqueUnderConcurrency() throws Exception {
        int threads = 16;
        int perThread = 5_000;
        Set<String> ids = ConcurrentHashMap.newKeySet();
        Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    ids.add(CorrelationIds.generateFallback());
                }
            }, "idgen-" + t);
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join(30_000);
        }

        int expected = threads * perThread;
        log.info("input: threads={} idsPerThread={} expected={}", threads, perThread, expected);
        log.info("judgement: distinctGenerated={} allAutoPrefixed={}",
                ids.size(), ids.stream().allMatch(id -> id.startsWith(CorrelationIds.FALLBACK_PREFIX)));

        assertEquals(expected, ids.size(), "fallback ids must be globally unique even in the same nanosecond");
        assertTrue(ids.stream().allMatch(id -> id.startsWith(CorrelationIds.FALLBACK_PREFIX)),
                "fallback ids must be distinguishable from inbound ids");
    }

    @Test
    @DisplayName("Inbound ids are accepted only with the strict charset and length")
    void sanitizeIncoming() {
        assertAccepted("abc-123_XYZ:9.0");
        assertAccepted("  trimmed-id  ", "trimmed-id");

        String[] rejectedInputs = {
                null, "", "   ",
                "has space", "tab\there", "line\nbreak",
                "semi;colon", "dollar$", "<script>",
                "x".repeat(65)
        };
        for (String raw : rejectedInputs) {
            String result = CorrelationIds.sanitizeIncoming(raw, 64);
            log.info("input={} judgement={}", raw == null ? "<null>" : "'" + raw.replace("\n", "\\n") + "'",
                    result == null ? "REJECTED" : "ACCEPTED(" + result + ")");
            assertNull(result, "input must be rejected");
        }
    }

    private void assertAccepted(String raw) {
        assertAccepted(raw, raw);
    }

    private void assertAccepted(String raw, String expected) {
        String result = CorrelationIds.sanitizeIncoming(raw, 64);
        log.info("input='{}' judgement=ACCEPTED({})", raw, result);
        assertNotNull(result);
        assertEquals(expected, result);
        assertFalse(result.isBlank());
    }

    @Test
    @DisplayName("A too-long but charset-valid id is rejected, not truncated")
    void overlongCharsetValidIsRejected() {
        Set<String> one = new HashSet<>();
        one.add("x");
        String longId = "x".repeat(129);
        assertNull(CorrelationIds.sanitizeIncoming(longId, 64));
        log.info("input=129 x-chars judgement=REJECTED (hard length cap)");
    }
}
