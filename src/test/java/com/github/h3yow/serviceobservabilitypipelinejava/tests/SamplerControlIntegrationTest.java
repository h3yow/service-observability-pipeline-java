package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.InMemoryMetricsRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the sampler can be changed live and that counts reconcile across
 * two periods taken at different rates. Internal endpoints are not self-metered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SamplerControlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SamplerControlIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InMemoryMetricsRegistry registry;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void resetMetrics() {
        registry.reset();
    }

    @Test
    @DisplayName("Changing rate mid-run does not break total==kept+dropped across the boundary")
    void rateChangeKeepsInvariantAndInternalPathsAreNotMetered() {
        restTemplate.exchange(url("/internal/sampler?rate=1.0"),
                org.springframework.http.HttpMethod.POST, null, Map.class);
        for (int i = 0; i < 100; i++) {
            restTemplate.getForEntity(url("/demo/orders/{id}"), Map.class, "a" + i);
        }
        long afterPeriod1 = readGrandTotal();
        long kept1 = readGrandKept();
        log.info("input: period1 requests=100 @rate=1.0 judgement: total={} kept={}", afterPeriod1, kept1);
        assertEquals(100, afterPeriod1);
        assertEquals(100, kept1);

        // Lower rate; subsequent requests have mostly dropped latency, but totals stay exact.
        restTemplate.exchange(url("/internal/sampler?rate=0.0"),
                org.springframework.http.HttpMethod.POST, null, Map.class);
        for (int i = 0; i < 100; i++) {
            restTemplate.getForEntity(url("/demo/orders/{id}"), Map.class, "b" + i);
        }
        long afterPeriod2 = readGrandTotal();
        long kept2 = readGrandKept();
        log.info("input: period2 requests=100 @rate=0.0 judgement: total={} kept={} "
                + "(counts reconcile across rate change)", afterPeriod2, kept2);
        assertEquals(200, afterPeriod2, "all 200 requests counted regardless of rate");
        assertEquals(100, kept2, "only period-1 requests kept their latency detail");

        ResponseEntity<Map> invalidRate = restTemplate.exchange(
                url("/internal/sampler?rate=1.5"),
                org.springframework.http.HttpMethod.POST, null, Map.class);
        // InvalidRequestException is handled BEFORE the (excluded) metrics path matters;
        // response must still be a well-formed 400.
        assertEquals(HttpStatus.BAD_REQUEST, invalidRate.getStatusCode());
        log.info("input: rate=1.5 judgement: status=400 (rejected), rate stays {}", registry.samplingRate());
        assertEquals(0.0d, registry.samplingRate(), 1e-9);
        assertTrue(afterPeriod2 == readGrandTotal(), "reading /internal/metrics must not add metric rows");
    }

    @SuppressWarnings("unchecked")
    private long readGrandTotal() {
        Map<String, Object> body = restTemplate.getForEntity(url("/internal/metrics"), Map.class).getBody();
        Map<String, Object> totals = (Map<String, Object>) body.get("totals");
        log.info("metrics readout: samplingRate={} totals={}", body.get("samplingRate"), totals);
        assertEquals(Boolean.TRUE, totals.get("countInvariantHolds"));
        return ((Number) totals.get("totalCount")).longValue();
    }

    @SuppressWarnings("unchecked")
    private long readGrandKept() {
        Map<String, Object> body = restTemplate.getForEntity(url("/internal/metrics"), Map.class).getBody();
        Map<String, Object> totals = (Map<String, Object>) body.get("totals");
        return ((Number) totals.get("sampledCount")).longValue();
    }
}
