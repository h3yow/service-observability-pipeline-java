package com.github.h3yow.serviceobservabilitypipelinejava.obs.web;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.DimensionSanitizer;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClass;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClassifier;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.InMemoryMetricsRegistry;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.MetricKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Times each request and records exactly one bounded metric line.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Internal paths (metrics readout/sampler control) are skipped so observability
 *       endpoints never observe themselves.</li>
 *   <li>Endpoint = the resolved Spring template, else the single {@code UNMATCHED}
 *       label — never the raw URI.</li>
 *   <li>Result class comes from the exception handler's request attribute when an
 *       exception was mapped; otherwise it is derived from the response status. This
 *       covers both {@code @RestControllerAdvice} and container-level errors.</li>
 *   <li>Every request is recorded (counted); sampling inside the registry decides only
 *       whether its latency is retained.</li>
 * </ul>
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    /** Set by {@link GlobalExceptionHandler} so the filter sees the mapped class. */
    public static final String FAILURE_CLASS_ATTRIBUTE =
            "com.github.h3yow.serviceobservabilitypipelinejava.failureClass";

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsFilter.class);

    private final InMemoryMetricsRegistry registry;
    private final String internalPathPrefix;
    private final int endpointMaxLength;

    public RequestMetricsFilter(InMemoryMetricsRegistry registry,
                                String internalPathPrefix,
                                int endpointMaxLength) {
        this.registry = registry;
        this.internalPathPrefix = internalPathPrefix;
        this.endpointMaxLength = endpointMaxLength;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(internalPathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        FailureClass resultClass = FailureClass.UNCLASSIFIED;
        try {
            filterChain.doFilter(request, response);
            Object attributed = request.getAttribute(FAILURE_CLASS_ATTRIBUTE);
            if (attributed instanceof FailureClass fc) {
                resultClass = fc;
            } else {
                resultClass = FailureClassifier.fromHttpStatus(response.getStatus());
            }
        } catch (Exception ex) {
            // Exception escaped even the advice (e.g. ServletException wrappers):
            // classify defensively and rethrow so container error handling still runs.
            resultClass = FailureClassifier.classify(ex);
            throw ex;
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            record(request, response, resultClass, durationNanos);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response,
                        FailureClass resultClass, long durationNanos) {
        try {
            Object pattern = request.getAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String template = pattern instanceof String p && !"/**".equals(p) ? p : null;
            String endpoint = DimensionSanitizer.normalizeEndpoint(
                    template,
                    request.getRequestURI(),
                    endpointMaxLength);
            MetricKey key = new MetricKey(endpoint, resultClass);
            registry.record(key, durationNanos, CorrelationContext.current());
            log.debug("metric endpoint={} result={} status={} durationNanos={}",
                    endpoint, resultClass, response.getStatus(), durationNanos);
        } catch (RuntimeException metricFailure) {
            // Metrics must never break the request path.
            log.warn("Failed to record request metric", metricFailure);
        }
    }
}
