package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.RequestIdContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求 ID 上下文测试：兜底规则、跨线程传播、线程复用与并发隔离。
 * 每个用例都打印输入与判定依据。
 */
class RequestIdContextTest {

    private static final Logger log = LoggerFactory.getLogger(RequestIdContextTest.class);

    @Test
    @DisplayName("入口缺失/非法/合法三种输入得到可区分结果")
    void resolveInbound() {
        record Case(String raw, String expectPrefix, boolean expectGenerated) {
        }
        List<Case> cases = List.of(
                new Case(null, RequestIdContext.GEN_PREFIX, true),
                new Case("   ", RequestIdContext.GEN_PREFIX, true),
                new Case("bad id with space", RequestIdContext.BAD_PREFIX, true),
                new Case("a".repeat(65), RequestIdContext.BAD_PREFIX, true),
                new Case("upstream-abc_123", "upstream-abc_123", false)
        );
        for (Case c : cases) {
            RequestIdContext.Resolved r = RequestIdContext.resolveInbound(c.raw());
            String actualPrefix = c.expectGenerated() ? r.id().substring(0, 4) : r.id();
            log.info("输入 header='{}' => 判定 id={} generated={}；期望前缀='{}' generated={}",
                    c.raw(), r.id(), r.generated(), c.expectPrefix(), c.expectGenerated());
            assertEquals(c.expectPrefix, actualPrefix, "输入: " + c.raw());
            assertEquals(c.expectGenerated, r.generated());
        }
    }

    @Test
    @DisplayName("wrap 传播到工作线程，执行后工作线程上下文被清理")
    void wrapPropagatesAndCleansUp() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String parentId = "upstream-parent-1";
        try {
            Future<String> future;
            String beforeOnWorker;
            Callable<String> task = () -> {
                log.info("工作线程执行：期望 requestId={}，实际={}",
                        parentId, RequestIdContext.get());
                return RequestIdContext.get();
            };
            String seen = RequestIdContext.callWith(parentId, () -> pool.submit(RequestIdContext.wrap(task)).get());
            assertEquals(parentId, seen);

            // 线程复用后（无包装提交）不应残留旧 ID
            String afterReuse = pool.submit(() -> {
                String id = RequestIdContext.get();
                log.info("线程复用后无包装任务：期望 requestId=null，实际={}", id);
                return id;
            }).get();
            assertNull(afterReuse, "线程复用必须清理上下文，防止串号");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("无上下文提交的任务获得 bg- 兜底 ID")
    void backgroundFallback() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String id = pool.submit(RequestIdContext.wrap((Callable<String>) RequestIdContext::get)).get();
            log.info("无入口上下文提交后台任务 => 判定 requestId={}，期望以 bg- 开头", id);
            assertNotNull(id);
            assertTrue(id.startsWith(RequestIdContext.BG_PREFIX), () -> "实际: " + id);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("高并发下每个请求 ID 只出现在自己线程，不串号、不丢失")
    void concurrentIsolation() throws Exception {
        int threads = 64;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        ReentrantLock lock = new ReentrantLock();
        List<String> mismatches = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final String requestId = "req-" + t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        RequestIdContext.runWith(requestId, () -> {
                            String actual = RequestIdContext.get();
                            if (!requestId.equals(actual)) {
                                lock.lock();
                                try {
                                    mismatches.add("expected=" + requestId + " actual=" + actual);
                                } finally {
                                    lock.unlock();
                                }
                            }
                        });
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("并发隔离：threads={} iterations={} 总判定={}，不匹配数={}（期望 0）",
                threads, iterations, threads * iterations, mismatches.size());
        assertEquals(0, mismatches.size(), () -> "串号: " + mismatches.stream().limit(5).toList());
    }
}
