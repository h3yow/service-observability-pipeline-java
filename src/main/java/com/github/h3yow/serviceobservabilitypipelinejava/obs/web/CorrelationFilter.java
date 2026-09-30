package com.github.h3yow.serviceobservabilitypipelinejava.obs.web;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes the correlation id for every inbound request:
 * <ol>
 *   <li>Reuses a valid inbound {@code X-Correlation-Id} (strict charset/length).</li>
 *   <li>Generates a deterministic, {@code auto-}-prefixed fallback otherwise, logging
 *       why (missing / rejected), so the two origins are always distinguishable.</li>
 * </ol>
 * The id is bound to the request thread (ThreadLocal + MDC) and always cleared in
 * finally, and is echoed back in the response header. This filter is ordered ahead of
 * the metrics filter so every metric record can attribute its sampled decision to the
 * same id.
 */
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    private final int maxIncomingLength;

    public CorrelationFilter(int maxIncomingLength) {
        this.maxIncomingLength = maxIncomingLength;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER_NAME);
        String accepted = CorrelationIds.sanitizeIncoming(raw, maxIncomingLength);
        String correlationId;
        if (accepted != null) {
            correlationId = accepted;
        } else {
            correlationId = CorrelationIds.generateFallback();
            if (raw == null || raw.isBlank()) {
                log.debug("Inbound correlation id missing; generated fallback id={}", correlationId);
            } else {
                // Never log the raw value verbatim: it failed validation and could contain junk.
                log.info("Inbound correlation id rejected (length={}, blank={}); generated "
                        + "fallback id={}", raw.length(), raw.isBlank(), correlationId);
            }
        }

        response.setHeader(HEADER_NAME, correlationId);
        CorrelationContext.begin(correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.end();
        }
    }
}
