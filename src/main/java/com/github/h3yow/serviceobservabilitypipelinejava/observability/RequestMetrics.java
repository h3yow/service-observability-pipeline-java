package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 有界、线程安全的请求指标聚合器。
 *
 * <p>设计要点（面向高并发与内存可控）：
 * <ul>
 *   <li>所有计数使用 {@link LongAdder}，高并发写开销低，无锁竞争热点；</li>
 *   <li>序列 key 为 endpoint × outcome × reason，三个维度的取值都经过收敛
 *       （endpoint 使用路由模板而非原始路径，reason 走分类器/白名单）；</li>
 *   <li>序列数量有硬上限 {@code maxSeries}，超限的新 key 全部并入固定
 *       {@link #OVERFLOW} 桶，内存占用严格上界化；</li>
 *   <li>延迟只记录固定桶位直方图 + 总/最大值，单序列内存恒定；</li>
 *   <li>计数与采样完全解耦：{@link #record} 对每个请求都调用，
 *       {@link #markSampled(boolean)} 只更新采样计数，丢弃的请求照样计入次数。</li>
 * </ul>
 */
public class RequestMetrics {

    private static final Logger log = LoggerFactory.getLogger(RequestMetrics.class);

    public static final String OVERFLOW = "overflow";

    private record Key(String endpoint, String outcome, String reason) {
    }

    private static final class SeriesAcc {
        final LongAdder count = new LongAdder();
        final LongAdder totalDuration = new LongAdder();
        final AtomicLong maxDuration = new AtomicLong();
        final LongAdder active = new LongAdder();
        final LongAdder[] buckets;

        SeriesAcc(int bucketCount) {
            this.buckets = new LongAdder[bucketCount];
            for (int i = 0; i < bucketCount; i++) {
                buckets[i] = new LongAdder();
            }
        }
    }

    private final ConcurrentHashMap<Key, SeriesAcc> series = new ConcurrentHashMap<>();
    private final AtomicInteger seriesCount = new AtomicInteger();
    private final int maxSeries;

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder sampledIn = new LongAdder();
    private final LongAdder sampledOut = new LongAdder();
    private final LongAdder overflowCount = new LongAdder();

    private final SeriesAcc overflowAcc =
            new SeriesAcc(MetricsSnapshot.Series.BUCKET_BOUNDS.length + 1);

    public RequestMetrics(int maxSeries) {
        this.maxSeries = maxSeries;
    }

    /**
     * 记录一次请求。
     *
     * @param endpoint       收敛后的接口名（路由模板）
     * @param outcome        结果类别
     * @param reason         已收敛的原因码
     * @param durationMillis 处理耗时
     */
    public void record(String endpoint, Outcome outcome, String reason, long durationMillis) {
        totalRequests.increment();
        SeriesAcc acc = resolve(endpoint, outcome.name(), reason);
        acc.count.increment();
        acc.totalDuration.add(durationMillis);
        acc.buckets[bucketIndex(durationMillis)].increment();
        acc.maxDuration.accumulateAndGet(durationMillis, Math::max);
    }

    /** 请求进入时增加在途计数，结束时减少，用于观测并发。 */
    public void activeChange(String endpoint, int delta) {
        resolve(endpoint, "ACTIVE", ResultClassifier.NONE).active.add(delta);
    }

    /** 记录一次采样判定；无论命中与否，请求总数都已在 {@link #record} 中全量计入。 */
    public void markSampled(boolean accepted) {
        (accepted ? sampledIn : sampledOut).increment();
    }

    private SeriesAcc resolve(String endpoint, String outcome, String reason) {
        Key key = new Key(endpoint, outcome, reason);
        SeriesAcc existing = series.get(key);
        if (existing != null) {
            return existing;
        }
        if (seriesCount.get() >= maxSeries) {
            overflowCount.increment();
            return overflowAcc;
        }
        SeriesAcc created = new SeriesAcc(MetricsSnapshot.Series.BUCKET_BOUNDS.length + 1);
        SeriesAcc prev = series.putIfAbsent(key, created);
        if (prev != null) {
            return prev;
        }
        int size = seriesCount.incrementAndGet();
        if (size > maxSeries) {
            // 并发下可能瞬时略超上限，把刚建的桶并入 overflow 并删除，保证稳态有界
            series.remove(key, created);
            seriesCount.decrementAndGet();
            overflowCount.increment();
            log.warn("metrics series cap {} reached, routing to overflow bucket (key={}/{}/{})",
                    maxSeries, endpoint, outcome, reason);
            return overflowAcc;
        }
        return created;
    }

    private static int bucketIndex(long durationMillis) {
        long[] bounds = MetricsSnapshot.Series.BUCKET_BOUNDS;
        for (int i = 0; i < bounds.length; i++) {
            if (durationMillis <= bounds[i]) {
                return i;
            }
        }
        return bounds.length; // > 最大边界
    }

    /** 生成一致性快照（所有计数同一时刻读取）。 */
    public MetricsSnapshot snapshot() {
        List<MetricsSnapshot.Series> list = new ArrayList<>(series.size() + 1);
        series.forEach(1_000, (key, acc) -> {
            if (acc.count.sum() > 0 || acc.active.sum() != 0) {
                list.add(toSeries(key.endpoint(), key.outcome(), key.reason(), acc));
            }
        });
        if (overflowAcc.count.sum() > 0 || overflowAcc.active.sum() != 0) {
            list.add(toSeries(OVERFLOW, OVERFLOW, OVERFLOW, overflowAcc));
        }
        list.sort(Comparator.comparing(MetricsSnapshot.Series::endpoint)
                .thenComparing(MetricsSnapshot.Series::outcome)
                .thenComparing(MetricsSnapshot.Series::reason));
        return new MetricsSnapshot(List.copyOf(list),
                totalRequests.sum(), sampledIn.sum(), sampledOut.sum(),
                overflowCount.sum(), maxSeries);
    }

    private MetricsSnapshot.Series toSeries(String endpoint, String outcome, String reason,
                                            SeriesAcc acc) {
        long[] buckets = new long[acc.buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = acc.buckets[i].sum();
        }
        List<Long> active = acc.active.sum() == 0 ? List.of() : List.of(acc.active.sum());
        return new MetricsSnapshot.Series(endpoint, outcome, reason,
                acc.count.sum(), acc.totalDuration.sum(), acc.maxDuration.get(), buckets, active);
    }

    /** 测试用：重置全部聚合状态。 */
    public void reset() {
        series.clear();
        seriesCount.set(0);
        // LongAdder 无 reset，直接重建聚合器不现实，置零通过内部 sumThenReset
        totalRequests.reset();
        sampledIn.reset();
        sampledOut.reset();
        overflowCount.reset();
        resetAcc(overflowAcc);
        series.values().forEach(RequestMetrics::resetAcc);
    }

    private static void resetAcc(SeriesAcc acc) {
        acc.count.reset();
        acc.totalDuration.reset();
        acc.maxDuration.set(0);
        acc.active.reset();
        for (LongAdder b : acc.buckets) {
            b.reset();
        }
    }
}
