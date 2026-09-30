package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部可观测性端点（不经过业务路径，指标中以各自路由模板呈现）。
 *
 * <ul>
 *   <li>{@code GET /internal/metrics}：当前指标快照；</li>
 *   <li>{@code GET /internal/sampler}：当前采样率；</li>
 *   <li>{@code POST /internal/sampler?rate=}：运行期调整采样率（计数不受影响，前后可对账）。</li>
 * </ul>
 */
@RestController
public class InternalMetricsController {

    private final RequestMetrics metrics;
    private final RateSampler sampler;

    public InternalMetricsController(RequestMetrics metrics, RateSampler sampler) {
        this.metrics = metrics;
        this.sampler = sampler;
    }

    @GetMapping("/internal/metrics")
    public MetricsSnapshot metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/internal/sampler")
    public SamplerView samplerView() {
        return new SamplerView(sampler.getRate());
    }

    @PostMapping("/internal/sampler")
    public SamplerView updateSampler(@RequestParam("rate") double rate) {
        sampler.setRate(rate);
        return new SamplerView(sampler.getRate());
    }

    public record SamplerView(double rate) {
    }
}
