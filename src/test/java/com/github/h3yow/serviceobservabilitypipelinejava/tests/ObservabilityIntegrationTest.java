package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.DimensionSanitizer;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClass;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.InMemoryMetricsRegistry;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.MetricSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests through the real filter chain: concurrent id propagation,
 * exception classification by cause, async/job propagation, and the count invariant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InMemoryMetricsRegistry registry;

    @BeforeEach
    void resetMetrics() {
        registry.reset();
        registry.setSamplingRate(1.0d);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("Concurrent requests keep their own correlation id end-to-end and never cross-talk")
    void concurrentRequestsDoNotCrossTalk() throws Exception {
        int threads = 32;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int rawT = 0; rawT < threads; rawT++) {
            final int t = rawT;
            final String cid = "client-id-" + t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int raw = 0; raw < perThread; raw++) {
                    final int i = raw;
                    String expected = cid + "-" + i;
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("X-Correlation-Id", expected);
                    HttpEntity<Void> entity = new HttpEntity<>(headers);
                    ResponseEntity<Map> resp = restTemplate.exchange(
                            url("/demo/orders/order-" + t + "-" + i + "?seq=" + i),
                            HttpMethod.GET, entity, Map.class);
                    Object header = resp.getHeaders().getFirst("X-Correlation-Id");
                    Object bodyCid = resp.getBody() == null ? null : resp.getBody().get("correlationId");
                    if (resp.getStatusCode() != HttpStatus.OK
                            || !expected.equals(header)
                            || !expected.equals(bodyCid)) {
                        failures.incrementAndGet();
                        log.error("CROSS-TALK input cid={} got header={} body={}", expected, header, bodyCid);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        int total = threads * perThread;
        long recorded = bucketTotal("/demo/orders/{id}", FailureClass.SUCCESS);
        log.info("input: threads={} perThread={} total={} judgement: mismatches={} metricCount={}",
                threads, perThread, total, failures.get(), recorded);
        assertEquals(0, failures.get(), "each request must see only its own id");
        assertEquals(total, recorded, "every concurrent request must be counted exactly once");
    }

    @Test
    @DisplayName("Missing / invalid inbound id yields a distinct auto- fallback echoed to caller")
    void fallbackCorrelationIds() {
        ResponseEntity<Map> noHeader = restTemplate.getForEntity(url("/demo/orders/100"), Map.class);
        String generated = noHeader.getHeaders().getFirst("X-Correlation-Id");
        assertNotNull(generated);
        assertTrue(generated.startsWith("auto-"), "missing id must produce an auto- fallback");
        log.info("input: no header judgement: response id={} (auto- fallback)", generated);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "bad id with spaces");
        ResponseEntity<Map> invalid = restTemplate.exchange(
                url("/demo/orders/101"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        String replaced = invalid.getHeaders().getFirst("X-Correlation-Id");
        assertNotNull(replaced);
        assertTrue(replaced.startsWith("auto-"), "invalid id must be replaced by a fallback");
        assertFalse("bad id with spaces".equals(replaced));
        log.info("input: header='bad id with spaces' judgement: response id={} (rejected -> fallback)", replaced);
    }

    @Test
    @DisplayName("Failure paths are categorized distinctly and raw internals never reach the client")
    void failureCategoriesAndSanitizedErrors() {
        expectFailure("/demo/orders?id=abc", "/demo/orders",
                HttpStatus.BAD_REQUEST, FailureClass.VALIDATION, "VALIDATION_ERROR");
        expectFailure("/demo/timeout", "/demo/timeout",
                HttpStatus.GATEWAY_TIMEOUT, FailureClass.TIMEOUT, "DOWNSTREAM_TIMEOUT");
        expectFailure("/demo/downstream", "/demo/downstream",
                HttpStatus.BAD_GATEWAY, FailureClass.DOWNSTREAM, "DOWNSTREAM_ERROR");

        ResponseEntity<Map> reject = restTemplate.postForEntity(url("/demo/orders/reject"), null, Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, reject.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = reject.getBody();
        assertNotNull(body);
        String message = String.valueOf(body.get("message"));
        String code = String.valueOf(body.get("errorCode"));
        log.info("input: POST /demo/orders/reject judgement: status=422 code={} message='{}'", code, message);
        assertEquals("BUSINESS_REJECTED", code);
        assertTrue(bucketTotal("/demo/orders/reject", FailureClass.BUSINESS_REJECTED) >= 1,
                "business rejection must be recorded in its own bounded category");

        // The downstream body must not leak the simulated hostname/exception text.
        ResponseEntity<Map> downstream = restTemplate.getForEntity(url("/demo/downstream"), Map.class);
        String serialized = String.valueOf(downstream.getBody());
        assertFalse(serialized.contains("10.4.2.7"), "internal host detail must not be serialized");
        assertFalse(serialized.contains("IOException"), "exception type must not be serialized");
        log.info("input: /demo/downstream judgement: body='{}' contains no internal detail", serialized);
    }

    private void expectFailure(String path, String expectedEndpoint, HttpStatus status,
                               FailureClass resultClass, String errorCode) {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url(path), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        log.info("input: GET {} judgement: status={} expectedStatus={}, code={} expectedCode={}, metricBucket={}/{}",
                path, resp.getStatusCode().value(), status.value(),
                body.get("errorCode"), errorCode, expectedEndpoint, resultClass);
        assertEquals(status, resp.getStatusCode());
        assertEquals(errorCode, body.get("errorCode"));
        assertNotNull(body.get("correlationId"));
        assertTrue(bucketTotal(expectedEndpoint, resultClass) >= 1,
                () -> "expected a metric for " + expectedEndpoint + "/" + resultClass);
    }

    @Test
    @DisplayName("Async work and triggered background jobs carry the request correlation id")
    void asyncAndJobPropagation() throws Exception {
        String cid = "async-trace-1";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", cid);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> asyncResp = restTemplate.exchange(
                url("/demo/async"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(Boolean.TRUE, asyncResp.getBody().get("propagated"));
        assertEquals(cid, asyncResp.getBody().get("workerCorrelationId"));
        log.info("input: /demo/async cid={} judgement: workerSaw={} propagated={}",
                cid, asyncResp.getBody().get("workerCorrelationId"),
                asyncResp.getBody().get("propagated"));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> jobResp = restTemplate.exchange(
                url("/demo/jobs"), HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertEquals(Boolean.TRUE, jobResp.getBody().get("propagated"));
        assertEquals(cid, jobResp.getBody().get("jobCorrelationId"));
        log.info("input: POST /demo/jobs cid={} judgement: jobSaw={} propagated={}",
                cid, jobResp.getBody().get("jobCorrelationId"), jobResp.getBody().get("propagated"));
    }

    @Test
    @DisplayName("Counting stays accurate under sampling; metrics endpoint reports a holding invariant")
    void sampledRequestsRemainCounted() {
        registry.setSamplingRate(0.3d);
        int n = 600;
        for (int i = 0; i < n; i++) {
            restTemplate.getForEntity(url("/demo/orders/{id}"), Map.class, "ord" + i);
        }
        MetricSnapshot snap = findBucket("/demo/orders/{id}", FailureClass.SUCCESS);
        assertNotNull(snap);
        log.info("input: requests={} rate=0.3 judgement: total={} kept={} dropped={} invariant={}",
                n, snap.totalCount(), snap.sampledCount(), snap.droppedCount(),
                snap.totalCount() == snap.sampledCount() + snap.droppedCount());
        assertEquals(n, snap.totalCount());
        assertEquals(n, snap.sampledCount() + snap.droppedCount());
        // Kept subset roughly tracks 30% while totals remain exact.
        double keptRatio = snap.sampledCount() / (double) n;
        assertTrue(keptRatio > 0.15d && keptRatio < 0.45d,
                "kept latency subset should approximate 0.3, got " + keptRatio);
    }

    @Test
    @DisplayName("Unknown paths fold into one UNMATCHED label instead of exploding cardinality")
    void unknownPathsConverge() {
        for (int i = 0; i < 20; i++) {
            restTemplate.getForEntity(url("/no/such/resource/" + i), Map.class);
        }
        long unmatched = bucketTotal(DimensionSanitizer.UNMATCHED_ENDPOINT, FailureClass.NOT_FOUND);
        log.info("input: 20 random unknown paths judgement: UNMATCHED/notFound count={}", unmatched);
        assertEquals(20, unmatched);
    }

    private long bucketTotal(String endpoint, FailureClass resultClass) {
        MetricSnapshot snap = findBucket(endpoint, resultClass);
        return snap == null ? 0L : snap.totalCount();
    }

    private MetricSnapshot findBucket(String endpoint, FailureClass resultClass) {
        return registry.snapshot().stream()
                .filter(s -> s.endpoint().equals(endpoint) && s.resultClass() == resultClass)
                .findFirst().orElse(null);
    }
}
