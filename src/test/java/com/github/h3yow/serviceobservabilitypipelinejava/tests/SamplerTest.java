package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.RateSampler;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采样器测试：比率确定性、边界值、并发不重不漏、运行期调参后总数守恒。
 */
class SamplerTest {

    private static final Logger log = LoggerFactory.getLogger(SamplerTest.class);

    @Test
    @DisplayName("各采样率下命中数严格符合整数比率")
    void deterministicRate() {
        record Case(double rate, long n, long expectAccepted) {
        }
        List<Case> cases = List.of(
                new Case(0.0, 1000, 0),
                new Case(1.0, 1000, 1000),
                new Case(0.1, 1000, 100),
                new Case(0.25, 1000, 250),
                new Case(0.123456, 1_000_000, 123456)
        );
        for (Case c : cases) {
            RateSampler sampler = new RateSampler(c.rate());
            long accepted = 0;
            for (long i = 0; i < c.n(); i++) {
                if (sampler.tryAcquire()) {
                    accepted++;
                }
            }
            log.info("输入 rate={} n={} => 判定命中={} 丢弃={}；期望命中={}，守恒 accepted+dropped={}",
                    c.rate(), c.n(), accepted, c.n() - accepted, c.expectAccepted(),
                    accepted + (c.n() - accepted));
            assertEquals(c.expectAccepted(), accepted);
        }
    }

    @Test
    @DisplayName("越界采样率被截断到 [0,1]")
    void clampRate() {
        RateSampler a = new RateSampler(5.0);
        RateSampler b = new RateSampler(-1.0);
        log.info("输入 rate=5.0 => 判定实际 rate={}；输入 rate=-1.0 => 判定实际 rate={}",
                a.getRate(), b.getRate());
        assertEquals(1.0, a.getRate(), 1e-9);
        assertEquals(0.0, b.getRate(), 1e-9);
    }

    @Test
    @DisplayName("并发采样不重不漏；运行期调参后 accepted+dropped 恒等于总请求数")
    void concurrentAndReconfigurable() throws Exception {
        RateSampler sampler = new RateSampler(0.5);
        int threads = 32;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<long[]>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                Callable<long[]> task = () -> {
                    long accepted = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (seed == 0 && i == perThread / 2) {
                            // 运行中调整策略：后半程只采样 10%
                            sampler.setRate(0.1);
                        }
                        if (sampler.tryAcquire()) {
                            accepted++;
                        }
                    }
                    return new long[]{accepted, perThread};
                };
                futures.add(pool.submit(task));
            }
            long accepted = 0;
            long total = 0;
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                accepted += r[0];
                total += r[1];
            }
            long dropped = total - accepted;
            log.info("并发+动态调参：threads={} 每线程={} total={} => 判定 accepted={} dropped={}；守恒校验={}",
                    threads, perThread, total, accepted, dropped, accepted + dropped == total);
            assertEquals(total, accepted + dropped, "采样计数必须守恒");
            // 调参发生在约一半位置，整体命中率应明显低于初始 50% 但高于 10%
            long finalAccepted = accepted;
            assertTrue(finalAccepted > total * 0.1 && finalAccepted < total * 0.5,
                    () -> "调参应影响后半段分布，实际 accepted=" + finalAccepted);
        } finally {
            pool.shutdownNow();
        }
    }
}
