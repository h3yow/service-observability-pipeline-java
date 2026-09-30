package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

/**
 * Convergence rules for the endpoint dimension.
 *
 * <p>The raw request URI must never be used as a label (path/query can carry user
 * input like {@code /users/13900037460} or tokens and would make the metric set
 * unbounded). Instead:
 * <ol>
 *   <li>When Spring resolved a handler mapping pattern
 *       ({@code /users/{id}}) that template is used — ids are already folded away.</li>
 *   <li>When no pattern exists (static resources, unmapped 404 paths) requests are
 *       folded into a single {@link #UNMATCHED_ENDPOINT} label, not labeled per URI.</li>
 *   <li>When even the URI is unavailable, {@link #UNKNOWN_ENDPOINT} is used.</li>
 *   <li>The result is hard-capped at {@code maxLength}; an over-long value is
 *       truncated and tagged with a {@code ~} sentinel so it stays a single label.</li>
 * </ol>
 * The outcome dimension is always one of the closed {@link FailureClass} enum, so the
 * total cardinality is at most (#distinct templates + 2) * #FailureClass, and the
 * registry enforces an additional hard cap on the number of endpoint labels.
 */
public final class DimensionSanitizer {

    /** All requests that did not match a handler template share this label. */
    public static final String UNMATCHED_ENDPOINT = "UNMATCHED";

    /** Used when neither a template nor a URI is available. */
    public static final String UNKNOWN_ENDPOINT = "UNKNOWN";

    private static final String TRUNCATION_MARK = "~";

    private DimensionSanitizer() {
    }

    /**
     * Resolves the bounded endpoint label.
     *
     * @param pattern   the Spring best-matching pattern, or {@code null}
     * @param rawUri    the raw request URI, or {@code null}
     * @param maxLength hard cap on label length (inclusive of the truncation marker)
     */
    public static String normalizeEndpoint(String pattern, String rawUri, int maxLength) {
        String label;
        if (pattern != null && !pattern.isBlank()) {
            label = pattern;
        } else if (rawUri != null && !rawUri.isBlank()) {
            label = UNMATCHED_ENDPOINT;
        } else {
            label = UNKNOWN_ENDPOINT;
        }
        int cap = Math.max(TRUNCATION_MARK.length() + 1, maxLength);
        if (label.length() > cap) {
            label = label.substring(0, cap - TRUNCATION_MARK.length()) + TRUNCATION_MARK;
        }
        return label;
    }
}
