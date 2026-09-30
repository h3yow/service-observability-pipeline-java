package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.MetricsSnapshot;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.RequestMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：
 * <ol>
 *   <li>上游 X-Request-Id 贯穿同步与异步，响应头原样回传；</li>
 *   <li>缺失时 gen- 兜底；非法时 bad- 兜底；</li>
 *   <li>参数/业务/下游/超时四类失败各归各类，错误体不含底层细节；</li>
 *   <li>高并发下请求 ID 不串号、指标次数精确；</li>
 *   <li>路由模板收敛：不同路径变量落到同一条序列。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestObservabilityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RequestMetrics metrics;

    @BeforeEach
    void resetMetrics() {
        metrics.reset();
    }

    @Test
    @DisplayName("同步请求：上游 ID 贯穿并回传")
    void inboundIdEchoed() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders")
                        .header("X-Request-Id", "trace-sync-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemId":"item-1","mode":"ok"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String echo = result.getResponse().getHeader("X-Request-Id");
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        log.info("输入 X-Request-Id=trace-sync-001 => 判定 响应头={} body.requestId={}",
                echo, body.path("requestId").asText());
        assertEquals("trace-sync-001", echo);
        assertEquals("trace-sync-001", body.path("requestId").asText());
    }

    @Test
    @DisplayName("缺失与非法 ID：gen- 与 bad- 两种可区分兜底")
    void fallbackIds() throws Exception {
        MvcResult missing = mockMvc.perform(get("/orders/10")).andReturn();
        MvcResult invalid = mockMvc.perform(get("/orders/10")
                .header("X-Request-Id", "bad header value!!")).andReturn();
        String gen = missing.getResponse().getHeader("X-Request-Id");
        String bad = invalid.getResponse().getHeader("X-Request-Id");
        log.info("判定：缺失=>{} gen-={}；非法=>{} bad-={}", gen, gen.startsWith("gen-"),
                bad, bad.startsWith("bad-"));
        assertTrue(gen.startsWith("gen-"));
        assertTrue(bad.startsWith("bad-"));
        assertFalse(gen.equals(bad));
    }

    @Test
    @DisplayName("四类失败：状态码/错误码/归类正确，且不泄露底层异常")
    void failureClassification() throws Exception {
        record Case(String name, String content, int status, String codeContains, String notLeak) {
        }
        List<Case> cases = List.of(
                new Case("参数问题", """
                        {"itemId":"","mode":"x"}""", 400, "INVALID_ARGUMENT", null),
                new Case("业务拒绝", """
                        {"itemId":"i1","mode":"risk"}""", 422, "BUSINESS_REJECTED:STOCK_LIMIT", null),
                new Case("下游异常", """
                        {"itemId":"i1","mode":"downstream"}""", 502, "DOWNSTREAM_ERROR",
                        "10.0.0.9"),
                new Case("超时", """
                        {"itemId":"i1","mode":"timeout"}""", 504, "TIMEOUT", "Read timed out")
        );
        for (Case c : cases) {
            MvcResult r = mockMvc.perform(post("/orders")
                            .header("X-Request-Id", "trace-fail-" + c.status())
                            .contentType(MediaType.APPLICATION_JSON).content(c.content()))
                    .andExpect(status().is(c.status())).andReturn();
            JsonNode body = MAPPER.readTree(r.getResponse().getContentAsString());
            String code = body.path("code").asText();
            log.info("输入[{}] => 判定 status={} code={} requestId={} message={}",
                    c.name(), r.getResponse().getStatus(), code,
                    body.path("requestId").asText(), body.path("message").asText());
            assertEquals(c.codeContains(), code, c.name());
            if (c.notLeak() != null) {
                assertFalse(r.getResponse().getContentAsString().contains(c.notLeak()),
                        "底层异常细节不得透出: " + c.notLeak());
            }
        }
        MetricsSnapshot snap = metrics.snapshot();
        log.info("失败链路指标对账 totalRequests={}（期望 4），各序列：", snap.totalRequests());
        snap.series().forEach(s -> log.info("  {} | {} | {} => count={}",
                s.endpoint(), s.outcome(), s.reason(), s.count()));
        assertEquals(4, snap.totalRequests());
        assertTrue(snap.series().stream().anyMatch(s ->
                s.outcome().equals("INVALID_ARGUMENT") && s.count() == 1));
        assertTrue(snap.series().stream().anyMatch(s ->
                s.outcome().equals("BUSINESS_REJECTED") && s.reason().equals("STOCK_LIMIT")));
        assertTrue(snap.series().stream().anyMatch(s -> s.outcome().equals("DOWNSTREAM_ERROR")));
        assertTrue(snap.series().stream().anyMatch(s -> s.outcome().equals("TIMEOUT")));
    }

    @Test
    @DisplayName("异步处理：请求 ID 跨线程池传播，结果与入口一致")
    void asyncPropagation() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/orders/async")
                        .param("itemId", "item-9")
                        .header("X-Request-Id", "trace-async-777"))
                .andReturn();
        mvcResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = MAPPER.readTree(mvcResult.getResponse().getContentAsString());
        log.info("异步链路判定 body={}，期望 requestId=trace-async-777", body);
        assertEquals("trace-async-777", body.path("requestId").asText());
        assertEquals(true, body.path("async").asBoolean());
    }

    @Test
    @DisplayName("路由模板收敛：不同路径变量只产生一条 GET /orders/{id} 序列")
    void endpointConvergence() throws Exception {
        for (String id : List.of("1", "2", "3", "4", "5")) {
            mockMvc.perform(get("/orders/" + id)
                    .header("X-Request-Id", "trace-conv-" + id)).andExpect(status().isOk());
        }
        long matched = metrics.snapshot().series().stream()
                .filter(s -> s.endpoint().equals("GET /orders/{id}"))
                .peek(s -> log.info("判定收敛序列 endpoint={} count={}", s.endpoint(), s.count()))
                .mapToLong(MetricsSnapshot.Series::count).sum();
        assertEquals(5, matched, "5 个不同路径变量必须收敛到同一条序列");
    }

    @Test
    @DisplayName("高并发：请求 ID 不串号，指标次数精确，响应头与输入一一对应")
    void concurrentRequests() throws Exception {
        int n = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String id = "trace-conc-" + i;
            tasks.add(() -> {
                MvcResult r = mockMvc.perform(post("/orders")
                                .header("X-Request-Id", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"itemId\":\"item-" + id + "\"}"))
                        .andExpect(status().isOk()).andReturn();
                String bodyId = MAPPER.readTree(r.getResponse().getContentAsString())
                        .path("requestId").asText();
                String headerId = r.getResponse().getHeader("X-Request-Id");
                boolean ok = id.equals(bodyId) && id.equals(headerId);
                if (!ok) {
                    log.error("串号！期望={} 实际 body={} header={}", id, bodyId, headerId);
                }
                return ok;
            });
        }
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        long mismatches = 0;
        for (Future<Boolean> f : results) {
            if (!f.get()) {
                mismatches++;
            }
        }
        pool.shutdownNow();
        MetricsSnapshot snap = metrics.snapshot();
        log.info("并发判定 n={} 串号数={}（期望 0）指标 totalRequests={} SUCCESS 计数={}",
                n, mismatches, snap.totalRequests(),
                snap.series().stream().filter(s -> s.outcome().equals("SUCCESS"))
                        .mapToLong(MetricsSnapshot.Series::count).sum());
        assertEquals(0, mismatches);
        assertEquals(n, snap.totalRequests());
    }
}
