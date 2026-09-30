package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.DimensionSanitizer;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClass;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClassifier;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.BusinessRejectedException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.DownstreamClientException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.DownstreamTimeoutException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for failure categorization and endpoint label convergence.
 */
class ClassificationAndDimensionsTest {

    private static final Logger log = LoggerFactory.getLogger(ClassificationAndDimensionsTest.class);

    @Test
    @DisplayName("Exceptions map to distinguishable, bounded failure classes (also via cause chain)")
    void classifyExceptions() {
        check(new InvalidRequestException("bad id"), FailureClass.VALIDATION);
        check(new BusinessRejectedException("no quota"), FailureClass.BUSINESS_REJECTED);
        check(new DownstreamClientException("call failed", new IOException("reset")),
                FailureClass.DOWNSTREAM);
        check(new DownstreamTimeoutException("slow"), FailureClass.TIMEOUT);
        check(new TimeoutException("read timed out"), FailureClass.TIMEOUT);
        // Wrapping does not hide the real cause: inner timeout wins through the chain.
        check(new RuntimeException("wrapper", new TimeoutException("inner")), FailureClass.TIMEOUT);
        check(new RuntimeException("totally unknown"), FailureClass.UNCLASSIFIED);
    }

    private void check(Throwable input, FailureClass expected) {
        FailureClass actual = FailureClassifier.classify(input);
        log.info("input: {} (cause={}) judgement: {} (expected {}) -> {}",
                input.getClass().getSimpleName(),
                input.getCause() == null ? "-" : input.getCause().getClass().getSimpleName(),
                actual, expected, actual == expected);
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("HTTP status fallback maps to the same bounded classes")
    void classifyStatuses() {
        assertEquals(FailureClass.SUCCESS, FailureClassifier.fromHttpStatus(200));
        assertEquals(FailureClass.SUCCESS, FailureClassifier.fromHttpStatus(204));
        assertEquals(FailureClass.VALIDATION, FailureClassifier.fromHttpStatus(400));
        assertEquals(FailureClass.VALIDATION, FailureClassifier.fromHttpStatus(422));
        assertEquals(FailureClass.UNAUTHENTICATED, FailureClassifier.fromHttpStatus(401));
        assertEquals(FailureClass.BUSINESS_REJECTED, FailureClassifier.fromHttpStatus(403));
        assertEquals(FailureClass.BUSINESS_REJECTED, FailureClassifier.fromHttpStatus(409));
        assertEquals(FailureClass.NOT_FOUND, FailureClassifier.fromHttpStatus(404));
        assertEquals(FailureClass.TIMEOUT, FailureClassifier.fromHttpStatus(504));
        assertEquals(FailureClass.DOWNSTREAM, FailureClassifier.fromHttpStatus(502));
        assertEquals(FailureClass.DOWNSTREAM, FailureClassifier.fromHttpStatus(503));
        assertEquals(FailureClass.UNCLASSIFIED, FailureClassifier.fromHttpStatus(500));
        assertEquals(FailureClass.UNCLASSIFIED, FailureClassifier.fromHttpStatus(599));
        log.info("input: representative status codes judgement: all mapped to bounded classes");
    }

    @Test
    @DisplayName("Endpoint dimension converges: template used, raw URIs fold to UNMATCHED, length capped")
    void endpointConvergence() {
        // Path variables never produce per-id labels.
        String a = DimensionSanitizer.normalizeEndpoint("/demo/orders/{id}", "/demo/orders/13900037460", 64);
        String b = DimensionSanitizer.normalizeEndpoint("/demo/orders/{id}", "/demo/orders/88888888", 64);
        assertEquals("/demo/orders/{id}", a);
        assertEquals(a, b, "different ids must converge to the same template label");

        // Unmapped paths all collapse to one label regardless of the raw URI.
        String u1 = DimensionSanitizer.normalizeEndpoint(null, "/totally/unknown/a?token=abc", 64);
        String u2 = DimensionSanitizer.normalizeEndpoint(null, "/totally/unknown/b", 64);
        assertEquals(DimensionSanitizer.UNMATCHED_ENDPOINT, u1);
        assertEquals(u1, u2);

        // Length cap keeps one bounded label with a truncation marker.
        String longPattern = "/x".repeat(100);
        String capped = DimensionSanitizer.normalizeEndpoint(longPattern, null, 32);
        assertTrue(capped.length() <= 32 && capped.endsWith("~"), "over-long labels must be truncated");

        log.info("input: /orders/139.. & /orders/888.. judgement: label='{}' (converged)", a);
        log.info("input: two random unknown URIs judgement: label='{}' (folded)", u1);
        log.info("input: 200-char template cap=32 judgement: label='{}' len={}", capped, capped.length());
    }
}
