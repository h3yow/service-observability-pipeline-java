package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

import java.util.Locale;

/**
 * Deterministic, allocation-light per-correlation-id sampler.
 *
 * <p>The decision for an id is {@code kept iff hash(id) mod 10000 < rate*10000}.
 * Consequences:
 * <ul>
 *   <li><b>Deterministic:</b> the same id is always kept or always dropped at a given
 *       rate, so retries and cross-component reports for one request agree.</li>
 *   <li><b>Stable across rate changes:</b> the kept set at rate p is a prefix of the
 *       kept set at any higher rate. Raising the rate only adds ids; lowering it only
 *       removes some — "before vs after" comparisons stay explainable.</li>
 *   <li><b>Cheap and contention-free:</b> one hash over a short string plus a volatile
 *       read of the rate; there is no counter, random draw or lock per request.</li>
 * </ul>
 * Rate range: {@code 0.0} (drop all duration detail) to {@code 1.0} (keep all).
 * Counting every request happens regardless of this decision, in the registry.
 */
public class DeterministicSampler {

    static final int BUCKETS = 10_000;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private volatile int threshold;

    public DeterministicSampler(double initialRate) {
        setRate(initialRate);
    }

    /**
     * @return {@code true} when duration detail for this id is kept
     */
    public boolean sample(String correlationId) {
        if (correlationId == null || threshold <= 0) {
            return false;
        }
        if (threshold >= BUCKETS) {
            return true;
        }
        return bucket(correlationId) < threshold;
    }

    public double rate() {
        return threshold / (double) BUCKETS;
    }

    /**
     * Updates the rate atomically; subsequent decisions use it immediately. Existing
     * recorded aggregates are left untouched, so totals remain reconcilable.
     */
    public void setRate(double rate) {
        double clamped = Math.max(0.0d, Math.min(1.0d, rate));
        this.threshold = (int) Math.round(clamped * BUCKETS);
    }

    /**
     * FNV-1a 64-bit hash, folded into {@code [0, BUCKETS)}. Id chars are ASCII by
     * construction (see {@code CorrelationIds}); upper-casing makes the bucket
     * case-insensitive so a propagated id cannot be kept/dropped on casing alone.
     */
    private static int bucket(String correlationId) {
        String s = correlationId.toLowerCase(Locale.ROOT);
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= FNV_PRIME;
        }
        // xor-fold to mix high/low bits before the modulo
        long folded = (hash >>> 32) ^ hash;
        int bucket = (int) Math.floorMod(folded, (long) BUCKETS);
        return bucket;
    }
}
