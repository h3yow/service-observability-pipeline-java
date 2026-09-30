package com.github.h3yow.serviceobservabilitypipelinejava.obs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable observability settings bound to the {@code observability} prefix.
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private int correlationIdMaxLength = 64;
    private int endpointMaxLength = 64;
    private double metricsSamplingRate = 1.0d;
    private int metricsMaxEndpoints = 128;
    private String internalPathPrefix = "/internal/";

    public int getCorrelationIdMaxLength() {
        return correlationIdMaxLength;
    }

    public void setCorrelationIdMaxLength(int correlationIdMaxLength) {
        this.correlationIdMaxLength = correlationIdMaxLength;
    }

    public int getEndpointMaxLength() {
        return endpointMaxLength;
    }

    public void setEndpointMaxLength(int endpointMaxLength) {
        this.endpointMaxLength = endpointMaxLength;
    }

    public double getMetricsSamplingRate() {
        return metricsSamplingRate;
    }

    public void setMetricsSamplingRate(double metricsSamplingRate) {
        this.metricsSamplingRate = metricsSamplingRate;
    }

    public int getMetricsMaxEndpoints() {
        return metricsMaxEndpoints;
    }

    public void setMetricsMaxEndpoints(int metricsMaxEndpoints) {
        this.metricsMaxEndpoints = metricsMaxEndpoints;
    }

    public String getInternalPathPrefix() {
        return internalPathPrefix;
    }

    public void setInternalPathPrefix(String internalPathPrefix) {
        this.internalPathPrefix = internalPathPrefix;
    }
}
