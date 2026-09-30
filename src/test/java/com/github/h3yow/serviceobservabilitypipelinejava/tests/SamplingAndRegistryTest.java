package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.DeterministicSampler;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClass;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.InMemoryMetricsRegistry;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.MetricKey;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.MetricSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for deterministic sampling semantics and registry invariants.
 */
class SamplingAndRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(SamplingAndRegistryTest.class);

    @Test
    @DisplayName("Sampling is deterministic per id and stable: kept-set only grows with rate")
    void deterministicAndMonotonicKeptSet() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            ids.add("req-" + i);
        }

        Set<String> kept01 = collectKept(ids, 0.1d);
        Set<String> kept05 = collectKept(ids, 0.5d);
        Set<String> kept10 = collectKept(ids, 1.0d);

        // Boundary rates
        DeterministicSampler dropAll = new DeterministicSampler(0.0d);
        DeterministicSampler keepAll = new DeterministicSampler(1.0d);
        assertFalse(ids.stream().anyMatch(id -> dropAll.sample(id)), "rate=0 must drop all");
        assertTrue(ids.stream().allMatch(id -> keepAll.sample(id)), "rate=1 must keep all");

        log.info("input: ids={} judgement: kept@0.1={}, kept@0.5={}, kept@1.0={}",
                ids.size(), kept01.size(), kept05.size(), kept10.size());
        assertTrue(kept05.containsAll(kept01), "raising the rate must only add ids (prefix property)");
        assertTrue(kept10.containsAll(kept05), "raising the rate must only add ids (prefix property)");
        assertEquals(ids.size(), kept10.size());

        // Same id decided identically across two sampler instances at the same rate.
        DeterministicSampler a = new DeterministicSampler(0.37d);
        DeterministicSampler b = new DeterministicSampler(0.37d);
        long disagreements = ids.stream().filter(id -> a.sample(id) != b.sample(id)).count();
        log.info("input: two samplers @0.37 judgement: disagreements={} (expect 0)", disagreements);
        assertEquals(0, disagreements);

        // Ratio is roughly right (allow tolerance for hash spread).
        double ratio = kept05.size() / (double) ids.size();
        log.info("input: rate=0.5 judgement: observedKeptRatio={}", ratio);
        assertTrue(ratio > 0.45d && ratio < 0.55d, "kept ratio must approximate configured rate");
    }

    private Set<String> collectKept(List<String> ids, double rate) {
        DeterministicSampler sampler = new DeterministicSampler(rate);
        Set<String> kept = new HashSet<>();
        for (String id : ids) {
            if (sampler.sample(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    @Test
    @DisplayName("Registry: dropped requests are still counted and total == kept + dropped always")
    void countInvariantHoldsUnderSampling() {
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(128, 0.25d);
        MetricKey key = new MetricKey("/demo/orders/{id}", FailureClass.SUCCESS);
        int requests = 10_000;
        for (int i = 0; i < requests; i++) {
            registry.record(key, 1_000_000L, "id-" + i);
        }
        MetricSnapshot snap = registry.snapshot().get(0);
        log.info("input: requests={} rate=0.25 judgement: total={} kept={} dropped={}",
                requests, snap.totalCount(), snap.sampledCount(), snap.droppedCount());
        assertEquals(requests, snap.totalCount());
        assertEquals(requests, snap.sampledCount() + snap.droppedCount(),
                "dropped requests must remain counted: total == kept + dropped");
        // Duration stats derive from the kept subset only.
        assertEquals(snap.sampledCount() * 1_000_000L, snap.durationSumNanos());
        assertEquals(1_000_000L, snap.durationMinNanos());
        assertEquals(1_000_000L, snap.durationMaxNanos());
    }

    @Test
    @DisplayName("Registry: endpoint labels are hard-capped and overflow folds into __OTHER__")
    void endpointCardinalityIsBounded() {
        int cap = 4;
        InMemoryMetricsRegistry registry = new InMemoryMetricsRegistry(cap, 1.0d);
        for (int e = 0; e < 50; e++) {
            registry.record(new MetricKey("/uri/" + e, FailureClass.SUCCESS), 10L, "id-" + e);
        }
        long distinctEndpoints = registry.snapshot().stream()
                .map(MetricSnapshot::endpoint).distinct().count();
        long otherTotal = registry.snapshot().stream()
                .filter(s -> InMemoryMetricsRegistry.OTHER_ENDPOINT.equals(s.endpoint()))
                .mapToLong(MetricSnapshot::totalCount).sum();

        log.info("input: 50 distinct raw labels, cap={} judgement: distinctStored={} foldedIntoOTHER={}",
                cap, distinctEndpoints, otherTotal);
        assertTrue(distinctEndpoints <= cap + 1L, "at most cap labels plus the OTHER fold label");
        assertEquals(46L, otherTotal, "all overflow labels must land in the single OTHER bucket");
        assertTrue(registry.droppedForOverflowKeys() > 0L);
    }
}
