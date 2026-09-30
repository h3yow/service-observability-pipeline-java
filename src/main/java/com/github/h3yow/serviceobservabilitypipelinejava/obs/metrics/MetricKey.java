package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

/**
 * Metric dimensions: normalized endpoint template + bounded failure class.
 */
public record MetricKey(String endpoint, FailureClass resultClass) {
}
