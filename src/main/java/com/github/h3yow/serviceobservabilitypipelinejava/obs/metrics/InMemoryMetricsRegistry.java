package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, thread-safe in-memory aggregator for request metrics.
 *
 * <p><b>Counting vs sampling.</b> Every finished request increments {@code totalCount}
 * for its key. Separately each request is either <i>kept</i> (duration recorded) or
 * <i>dropped</i> by the sampler, and the two counters are maintained so that the
 * invariant {@code totalCount == sampledCount + droppedCount} always holds. Dropped
 * requests are therefore still counted exactly; only their latency detail is missing.
 * Changing the sampling rate cannot break the invariant, so periods taken under
 * different rates can still be reconciled on counts.
 *
 * <p><b>Bounded memory.</b> Keys are already bounded by construction (templates * the
 * closed {@link FailureClass} enum). As defense in depth, the number of distinct
 * endpoint templates is hard-capped: once the cap is reached, any new label is folded
 * into a single {@link #OTHER_ENDPOINT} key (per result class) and counted by an
 * overflow adder. Nothing keyed by raw user input is ever stored.
 *
 * <p><b>Cost.</b> Hot-path updates use {@link LongAdder} (striped, uncontended under
 * high concurrency); key creation is rare and guarded by a short lock. Snapshots are
 * taken off the hot path.
 */
public class InMemoryMetricsRegistry {

    /** Endpoint label used once the distinct-template cap is exceeded. */
    public static final String OTHER_ENDPOINT = "__OTHER__";

    private static final Logger log = LoggerFactory.getLogger(InMemoryMetricsRegistry.class);

    private final ConcurrentHashMap<MetricKey, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger distinctEndpoints = new AtomicInteger();
    private final java.util.Set<String> knownEndpoints = ConcurrentHashMap.newKeySet();
    private final ReentrantLock createLock = new ReentrantLock();
    private final LongAdder overflowKeys = new LongAdder();

    private final int maxEndpointCardinality;
    private final DeterministicSampler sampler;

    public InMemoryMetricsRegistry(int maxEndpointCardinality, double samplingRate) {
        this.maxEndpointCardinality = Math.max(1, maxEndpointCardinality);
        this.sampler = new DeterministicSampler(samplingRate);
    }

    /**
     * Records one finished request.
     */
    public void record(MetricKey key, long durationNanos, String correlationId) {
        resolve(key).record(durationNanos, sampler.sample(correlationId));
    }

    private Bucket resolve(MetricKey key) {
        Bucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        createLock.lock();
        try {
            existing = buckets.get(key);
            if (existing != null) {
                return existing;
            }
            if (knownEndpoints.contains(key.endpoint())
                    || distinctEndpoints.get() < maxEndpointCardinality) {
                knownEndpoints.add(key.endpoint());
                distinctEndpoints.set(knownEndpoints.size());
                Bucket created = new Bucket();
                buckets.put(key, created);
                return created;
            }
            overflowKeys.increment();
            log.warn("Endpoint label cap={} reached; folding label='{}' into {}",
                    maxEndpointCardinality, key.endpoint(), OTHER_ENDPOINT);
            return buckets.computeIfAbsent(
                    new MetricKey(OTHER_ENDPOINT, key.resultClass()), k -> new Bucket());
        } finally {
            createLock.unlock();
        }
    }

    /**
     * Point-in-time readout of every bucket, sorted for deterministic output.
     */
    public List<MetricSnapshot> snapshot() {
        List<MetricSnapshot> out = new ArrayList<>(buckets.size());
        buckets.forEach((key, bucket) -> out.add(bucket.snapshot(key)));
        out.sort(java.util.Comparator
                .comparing(MetricSnapshot::endpoint)
                .thenComparing(s -> s.resultClass().name()));
        return out;
    }

    /** Clears all aggregates (tests / explicit admin action only). */
    public void reset() {
        createLock.lock();
        try {
            buckets.clear();
            knownEndpoints.clear();
            distinctEndpoints.set(0);
            overflowKeys.reset();
        } finally {
            createLock.unlock();
        }
    }

    public double samplingRate() {
        return sampler.rate();
    }

    public void setSamplingRate(double rate) {
        sampler.setRate(rate);
    }

    /** @return how many requests were folded into {@link #OTHER_ENDPOINT} due to the cap */
    public long droppedForOverflowKeys() {
        return overflowKeys.sum();
    }

    /**
     * Mutable aggregate for one {@link MetricKey}.
     */
    private static final class Bucket {
        private final LongAdder totalCount = new LongAdder();
        private final LongAdder sampledCount = new LongAdder();
        private final LongAdder droppedCount = new LongAdder();
        private final LongAdder durationSumNanos = new LongAdder();
        private final java.util.concurrent.atomic.AtomicLong minDurationNanos =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
        private final java.util.concurrent.atomic.AtomicLong maxDurationNanos =
                new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

        void record(long durationNanos, boolean kept) {
            totalCount.increment();
            if (kept) {
                sampledCount.increment();
                durationSumNanos.add(durationNanos);
                minDurationNanos.accumulateAndGet(durationNanos, Math::min);
                maxDurationNanos.accumulateAndGet(durationNanos, Math::max);
            } else {
                droppedCount.increment();
            }
        }

        MetricSnapshot snapshot(MetricKey key) {
            long total = totalCount.sum();
            long sampled = sampledCount.sum();
            long min = minDurationNanos.get();
            long max = maxDurationNanos.get();
            return new MetricSnapshot(
                    key.endpoint(),
                    key.resultClass(),
                    total,
                    sampled,
                    droppedCount.sum(),
                    durationSumNanos.sum(),
                    sampled == 0 ? 0L : min,
                    sampled == 0 ? 0L : max);
        }
    }
}
