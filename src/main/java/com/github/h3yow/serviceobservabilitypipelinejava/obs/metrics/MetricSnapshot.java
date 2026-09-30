package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

/**
 * Immutable point-in-time snapshot of one {@link MetricKey} bucket.
 */
public record MetricSnapshot(
        String endpoint,
        FailureClass resultClass,
        long totalCount,
        long sampledCount,
        long droppedCount,
        long durationSumNanos,
        long durationMinNanos,
        long durationMaxNanos
) {
    public double averageDurationNanos() {
        return 0d;
    }
}
