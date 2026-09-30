package com.github.h3yow.serviceobservabilitypipelinejava.demo.web;

import com.github.h3yow.serviceobservabilitypipelinejava.demo.async.AsyncJobService;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Demo endpoints exercising every observability path.
 *
 * <table>
 *   <caption>endpoint → failure class</caption>
 *   <tr><td>GET  /demo/orders/{id}</td><td>SUCCESS (id folded into template)</td></tr>
 *   <tr><td>GET  /demo/orders</td><td>VALIDATION when {@code id} missing/odd format</td></tr>
 *   <tr><td>POST /demo/orders/reject</td><td>BUSINESS_REJECTED</td></tr>
 *   <tr><td>GET  /demo/downstream</td><td>DOWNSTREAM</td></tr>
 *   <tr><td>GET  /demo/timeout</td><td>TIMEOUT</td></tr>
 *   <tr><td>GET  /demo/async</td><td>SUCCESS; id checked on worker thread</td></tr>
 *   <tr><td>POST /demo/jobs</td><td>SUCCESS; background work keeps trigger id</td></tr>
 * </table>
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final AsyncJobService asyncJobs;

    public DemoController(AsyncJobService asyncJobs) {
        this.asyncJobs = asyncJobs;
    }

    @GetMapping("/demo/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) {
        log.info("fetching order id={}", id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", id);
        body.put("correlationId", CorrelationContext.current());
        return body;
    }

    @GetMapping("/demo/orders")
    public Map<String, Object> queryOrder(@RequestParam(value = "id", required = false) String id) {
        if (id == null || id.isBlank() || !id.matches("[0-9]{1,18}")) {
            throw new InvalidRequestException("id must be 1..18 digits, got: " + safe(id));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", id);
        body.put("correlationId", CorrelationContext.current());
        return body;
    }

    @PostMapping("/demo/orders/reject")
    public Map<String, Object> reject() {
        throw new BusinessRejectedException("quota exhausted");
    }

    @GetMapping("/demo/downstream")
    public Map<String, Object> downstream() {
        try {
            throw new java.io.IOException("simulated connection reset to inventory-svc/10.4.2.7:9200");
        } catch (java.io.IOException e) {
            throw new DownstreamClientException("inventory call failed", e);
        }
    }

    @GetMapping("/demo/timeout")
    public Map<String, Object> timeout() {
        try {
            TimeUnit.NANOSECONDS.sleep(1);
            throw new java.util.concurrent.TimeoutException("read timed out after 500ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamTimeoutException("interrupted while waiting");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new DownstreamTimeoutException(e.getMessage());
        }
    }

    @GetMapping("/demo/async")
    public Map<String, Object> asyncPath() throws Exception {
        String requestId = CorrelationContext.current();
        String workerId = asyncJobs.echoCorrelationId().get(5, TimeUnit.SECONDS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestCorrelationId", requestId);
        body.put("workerCorrelationId", workerId);
        body.put("propagated", requestId != null && requestId.equals(workerId));
        return body;
    }

    @PostMapping("/demo/jobs")
    public Map<String, Object> triggerBackgroundJob() {
        String triggerId = CorrelationContext.current();
        String jobId = asyncJobs.runBackgroundJob(triggerId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("triggerCorrelationId", triggerId);
        body.put("jobCorrelationId", jobId);
        body.put("propagated", triggerId != null && triggerId.equals(jobId));
        return body;
    }

    private static String safe(String raw) {
        // Avoid echoing oversized input into our own log lines.
        if (raw == null) {
            return "<null>";
        }
        return raw.length() <= 32 ? raw : raw.substring(0, 32) + "...";
    }
}
