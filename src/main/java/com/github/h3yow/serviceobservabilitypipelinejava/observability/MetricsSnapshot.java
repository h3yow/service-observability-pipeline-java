package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import java.util.List;

/**
 * 指标快照（不可变）。
 *
 * @param series            按 endpoint × outcome × reason 聚合的时间序列
 * @param totalRequests     全量请求数（不受采样影响）
 * @param sampledIn         采样命中（输出详细日志）次数
 * @param sampledOut        采样丢弃次数；恒有 total = sampledIn + sampledOut
 * @param overflowCount     因超过序列上限被并入 overflow 桶的请求数
 * @param maxSeries         配置的序列上限
 */
public record MetricsSnapshot(List<Series> series,
                              long totalRequests,
                              long sampledIn,
                              long sampledOut,
                              long overflowCount,
                              int maxSeries) {

    /** 单条时间序列：含次数与延迟分布（固定桶 + 总量，便于对账且内存恒定）。 */
    public record Series(String endpoint,
                         String outcome,
                         String reason,
                         long count,
                         long totalDurationMillis,
                         long maxDurationMillis,
                         long[] latencyBuckets,
                         List<Long> activeRequests) {

        /** 固定延迟桶边界（毫秒），取值收敛，不随请求输入增长。 */
        public static final long[] BUCKET_BOUNDS = {10, 50, 100, 250, 500, 1000, 2500, 5000};
    }
}
