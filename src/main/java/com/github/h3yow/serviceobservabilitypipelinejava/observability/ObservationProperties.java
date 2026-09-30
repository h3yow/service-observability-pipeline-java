package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 可观测性配置，前缀 {@code observability}。
 *
 * <ul>
 *   <li>{@code header-name}：入口请求 ID 的请求头名；</li>
 *   <li>{@code sample.rate}：详细访问日志采样率 [0,1]，运行期可通过
 *       {@link RateSampler#setRate(double)} 调整；<b>采样只影响详细日志，计数指标始终全量</b>；</li>
 *   <li>{@code metrics.max-series}：指标时间序列上限（endpoint × outcome × reason），
 *       超限后新序列并入 overflow 桶，防止维度爆炸；</li>
 *   <li>{@code allowed-business-reasons}：允许直接作为 reason 维度值的业务原因白名单，
 *       白名单外统一收敛为 {@code other}；</li>
 *   <li>{@code background-job.enabled} / {@code fixed-delay-ms}：演示后台任务开关与间隔。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "observability")
public class ObservationProperties {

    private String headerName = "X-Request-Id";
    private Sample sample = new Sample();
    private Metrics metrics = new Metrics();
    private List<String> allowedBusinessReasons = List.of("STOCK_LIMIT", "RISK_BLOCK");
    private BackgroundJob backgroundJob = new BackgroundJob();

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public Sample getSample() {
        return sample;
    }

    public void setSample(Sample sample) {
        this.sample = sample;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public List<String> getAllowedBusinessReasons() {
        return allowedBusinessReasons;
    }

    public void setAllowedBusinessReasons(List<String> allowedBusinessReasons) {
        this.allowedBusinessReasons = allowedBusinessReasons;
    }

    public BackgroundJob getBackgroundJob() {
        return backgroundJob;
    }

    public void setBackgroundJob(BackgroundJob backgroundJob) {
        this.backgroundJob = backgroundJob;
    }

    public static class Sample {
        /** 详细日志采样率，1.0 全采样，0.0 全部不输出详细日志（计数仍全量）。 */
        private double rate = 1.0;

        public double getRate() {
            return rate;
        }

        public void setRate(double rate) {
            this.rate = rate;
        }
    }

    public static class Metrics {
        /** 指标序列硬上限，防止异常取值导致内存无界增长。 */
        private int maxSeries = 200;

        public int getMaxSeries() {
            return maxSeries;
        }

        public void setMaxSeries(int maxSeries) {
            this.maxSeries = maxSeries;
        }
    }

    public static class BackgroundJob {
        /** 演示后台定时任务是否启用。 */
        private boolean enabled = false;
        private long fixedDelayMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
