package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 固定比率采样器。
 *
 * <p>判定规则（确定性、无随机、无浮点累计误差）：对第 {@code n} 次请求
 * （n 从 1 开始，由 {@link AtomicLong} 在并发下不重不漏地分配），
 * 当且仅当 {@code floor(n*rate) > floor((n-1)*rate)} 时命中。
 * 由此长期命中数严格等于 {@code round(N*rate)}，且命中位置均匀分散，
 * rate=0.1 时即每 10 次命中 1 次，不会出现前 1000 次全中的偏斜。
 *
 * <p>采样只作用于详细访问日志；次数/耗时指标对每次请求都记录。
 * 运行期 {@link #setRate(double)} 即时生效，调整前后
 * {@code 命中数 + 丢弃数 = 总请求数} 始终成立，结论可对账。
 */
public class RateSampler implements Sampler {

    private static final Logger log = LoggerFactory.getLogger(RateSampler.class);

    private final AtomicLong counter = new AtomicLong();
    private volatile double rate = 1.0;

    public RateSampler(double rate) {
        setRate(rate);
    }

    @Override
    public boolean tryAcquire() {
        long n = counter.incrementAndGet();
        double r = rate;
        return (long) Math.floor(n * r) > (long) Math.floor((n - 1) * r);
    }

    public double getRate() {
        return rate;
    }

    /** 运行期调整采样率，超出 [0,1] 的值会被截断。 */
    public void setRate(double newRate) {
        double clamped = Math.max(0.0, Math.min(1.0, newRate));
        this.rate = clamped;
        log.info("RateSampler rate updated: {}", clamped);
    }
}
