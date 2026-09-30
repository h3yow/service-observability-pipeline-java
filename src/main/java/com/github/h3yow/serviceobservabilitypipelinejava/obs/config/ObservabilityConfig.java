package com.github.h3yow.serviceobservabilitypipelinejava.obs.config;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.InMemoryMetricsRegistry;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.web.CorrelationFilter;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.web.RequestMetricsFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the observability beans and fixes filter ordering: the correlation filter
 * runs first so the metrics filter (and all downstream logging) already has an id.
 *
 * <p>Both filters are registered via {@link FilterRegistrationBean} (not component
 * scanning) to make their order and single registration explicit.
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public InMemoryMetricsRegistry metricsRegistry(ObservabilityProperties properties) {
        return new InMemoryMetricsRegistry(
                properties.getMetricsMaxEndpoints(),
                properties.getMetricsSamplingRate());
    }

    @Bean
    public FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration(
            ObservabilityProperties properties) {
        FilterRegistrationBean<CorrelationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationFilter(properties.getCorrelationIdMaxLength()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("correlationFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilterRegistration(
            InMemoryMetricsRegistry registry, ObservabilityProperties properties) {
        FilterRegistrationBean<RequestMetricsFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestMetricsFilter(
                registry,
                properties.getInternalPathPrefix(),
                properties.getEndpointMaxLength()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("requestMetricsFilter");
        return registration;
    }
}
