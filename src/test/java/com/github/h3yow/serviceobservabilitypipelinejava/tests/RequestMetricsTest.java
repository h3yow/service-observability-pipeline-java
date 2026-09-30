package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.MetricsSnapshot;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.Outcome;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.RequestMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标聚合器测试：聚合/对账、维度收敛、序列上限、并发不丢数。
 */
class RequestMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsTest.class);

    @Test
    @DisplayName("次数与延迟按 endpoint×outcome×reason 聚合，总和与全量计数对账")
    void aggregateAndReconcile() {
        RequestMetrics metrics = new RequestMetrics(50);
        // 路径变量不同但模板相同，应落到同一条序列
        metrics.record("POST /orders", Outcome.SUCCESS, "none", 5);
        metrics.record("POST /orders", Outcome.SUCCESS, "none", 30);
        metrics.record("POST /orders", Outcome.BUSINESS_REJECTED, "STOCK_LIMIT", 2);
        metrics.record("POST /orders", Outcome.TIMEOUT, "downstream_timeout", 1200);
        metrics.record("GET /orders/{id}", Outcome.SUCCESS, "none", 8);
        metrics.markSampled(true);
        metrics.markSampled(false);
        metrics.markSampled(true);

        MetricsSnapshot snap = metrics.snapshot();
        long sumCount = snap.series().stream().mapToLong(MetricsSnapshot.Series::count).sum();
        log.info("输入 5 次请求（2 成功+1 拒绝+1 超时 于 POST /orders；1 成功 于 GET /orders/{{id}}）");
        snap.series().forEach(s -> log.info(
                "判定序列 endpoint={} outcome={} reason={} count={} totalMs={} maxMs={} buckets={}",
                s.endpoint(), s.outcome(), s.reason(), s.count(), s.totalDurationMillis(),
                s.maxDurationMillis(), java.util.Arrays.toString(s.latencyBuckets())));
        log.info("对账：序列计数之和={} totalRequests={} sampledIn={} sampledOut={}",
                sumCount, snap.totalRequests(), snap.sampledIn(), snap.sampledOut());

        assertEquals(5, snap.totalRequests());
        assertEquals(5, sumCount);
        assertEquals(2, snap.sampledIn());
        assertEquals(1, snap.sampledOut());
        MetricsSnapshot.Series success = snap.series().stream()
                .filter(s -> s.endpoint().equals("POST /orders")
                        && s.outcome().equals("SUCCESS")).findFirst().orElseThrow();
        assertEquals(2, success.count());
        assertEquals(35, success.totalDurationMillis());
        assertEquals(30, success.maxDurationMillis());
        // 5ms -> <=10 桶(index0)；30ms -> <=50 桶(index1)
        assertEquals(1, success.latencyBuckets()[0]);
        assertEquals(1, success.latencyBuckets()[1]);
    }

    @Test
    @DisplayName("超过序列上限的新维度并入 overflow，内存有界且计数不丢")
    void seriesCapRoutesToOverflow() {
        int cap = 4;
        RequestMetrics metrics = new RequestMetrics(cap);
        int distinct = 20; // 20 个不同 reason，远超 cap
        for (int i = 0; i < distinct; i++) {
            metrics.record("POST /orders", Outcome.BUSINESS_REJECTED, "REASON_" + i, 1);
        }
        MetricsSnapshot snap = metrics.snapshot();
        long inNamed = snap.series().stream()
                .filter(s -> !s.endpoint().equals(RequestMetrics.OVERFLOW))
                .mapToLong(MetricsSnapshot.Series::count).sum();
        long inOverflow = snap.series().stream()
                .filter(s -> s.endpoint().equals(RequestMetrics.OVERFLOW))
                .mapToLong(MetricsSnapshot.Series::count).findFirst().orElse(0);
        log.info("输入 distinctReason={} cap={} => 判定 命名序列计数={} overflow计数={} overflowInc={}；总计数={}",
                distinct, cap, inNamed, inOverflow, snap.overflowCount(), snap.totalRequests());
        assertTrue(snap.series().size() <= cap + 1, "序列数必须有界");
        assertEquals(distinct, snap.totalRequests(), "超限请求不能丢");
        assertEquals(distinct, inNamed + inOverflow, "命名桶 + overflow 必须等于总数");
        assertTrue(inOverflow > 0);
    }

    @Test
    @DisplayName("高并发记录不丢数、不错桶：总计数精确等于提交次数")
    void concurrentNoLoss() throws Exception {
        RequestMetrics metrics = new RequestMetrics(100);
        int threads = 50;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        // 4 个固定 outcome 轮换，维度收敛
                        Outcome o = Outcome.values()[i % 4 == 3 ? 3 : i % 4];
                        metrics.record("POST /orders", o, switch (o) {
                            case SUCCESS -> "none";
                            case INVALID_ARGUMENT -> "invalid_request";
                            case BUSINESS_REJECTED -> "STOCK_LIMIT";
                            default -> "downstream_timeout";
                        }, i % 2000);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        MetricsSnapshot snap = metrics.snapshot();
        long sum = snap.series().stream().mapToLong(MetricsSnapshot.Series::count).sum();
        log.info("并发输入 threads={} perThread={} 期望总数={} => 判定 totalRequests={} 序列计数和={} 序列数={}",
                threads, perThread, threads * perThread, snap.totalRequests(), sum, snap.series().size());
        assertEquals((long) threads * perThread, snap.totalRequests());
        assertEquals((long) threads * perThread, sum);
    }
}
