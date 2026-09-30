package com.github.h3yow.serviceobservabilitypipelinejava.demo.web;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.InMemoryMetricsRegistry;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.MetricSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local observability readout/control. Everything under {@code /internal/} is excluded
 * from request metrics (see {@code RequestMetricsFilter#shouldNotFilter}), so scraping
 * these endpoints cannot pollute the numbers they expose.
 */
@RestController
public class InternalController {

    private final InMemoryMetricsRegistry registry;

    public InternalController(InMemoryMetricsRegistry registry) {
        this.registry = registry;
    }

    /** Aggregated counters and (for the sampled set) latency stats. */
    @GetMapping("/internal/metrics")
    public Map<String, Object> metrics() {
        List<MetricSnapshot> snapshots = registry.snapshot();

        long grandTotal = snapshots.stream().mapToLong(MetricSnapshot::totalCount).sum();
        long grandSampled = snapshots.stream().mapToLong(MetricSnapshot::sampledCount).sum();
        long grandDropped = snapshots.stream().mapToLong(MetricSnapshot::droppedCount).sum();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("samplingRate", registry.samplingRate());
        body.put("totals", Map.of(
                "totalCount", grandTotal,
                "sampledCount", grandSampled,
                "droppedCount", grandDropped,
                "countInvariantHolds", grandTotal == grandSampled + grandDropped));
        body.put("overflowFoldedRequests", registry.droppedForOverflowKeys());
        body.put("buckets", snapshots);
        return body;
    }

    /** Changes the sampler rate live; counts already recorded are untouched. */
    @PostMapping("/internal/sampler")
    public Map<String, Object> setSamplingRate(@RequestParam("rate") double rate) {
        if (Double.isNaN(rate) || rate < 0.0d || rate > 1.0d) {
            throw new InvalidRequestException("rate must be within [0.0, 1.0]");
        }
        registry.setSamplingRate(rate);
        return Map.of("samplingRate", registry.samplingRate());
    }
}
