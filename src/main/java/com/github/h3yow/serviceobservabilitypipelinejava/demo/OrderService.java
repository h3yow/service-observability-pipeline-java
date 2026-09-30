package com.github.h3yow.serviceobservabilitypipelinejava.demo;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.RequestIdContext;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.DownstreamServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * 演示服务：覆盖三类触发链路。
 * <ol>
 *   <li>同步调用：{@link #place}（HTTP 请求线程内，ID 自然贯穿）；</li>
 *   <li>异步处理：{@link #processAsync}（线程池，经上下文装饰器传播 ID）；</li>
 *   <li>后台任务：{@link #scheduledCleanup}（无入口请求，自动获得 bg- 兜底 ID）。</li>
 * </ol>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 同步下单：按 mode 注入不同故障，其余正常返回。 */
    public String place(OrderRequest request) {
        String itemId = request.itemId();
        String mode = request.mode() == null ? "" : request.mode();
        log.info("placing order itemId={} mode={} requestId={}",
                itemId, mode, RequestIdContext.get());

        if ("risk".equals(mode)) {
            throw new StockLimitException(itemId);
        }
        if ("downstream".equals(mode)) {
            throw new DownstreamServiceException("inventory-service",
                    "call failed", new RuntimeException("connection reset 10.x.x.x:9000"));
        }
        if ("timeout".equals(mode)) {
            throw DownstreamServiceException.timeout("inventory-service", 200,
                    new TimeoutException("Read timed out"));
        }
        if ("item-000".equals(itemId)) {
            // 另一个白名单内业务原因（库存兜底）
            throw new StockLimitException(itemId);
        }
        return "ORDER-" + itemId.toUpperCase();
    }

    /** 异步处理：在 observationExecutor 上执行，请求 ID 由装饰器传播。 */
    @Async("observationExecutor")
    public CompletableFuture<String> processAsync(String itemId) {
        log.info("async processing start itemId={} requestId={} thread={}",
                itemId, RequestIdContext.get(), Thread.currentThread().getName());
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("async processing done itemId={} requestId={}", itemId, RequestIdContext.get());
        return CompletableFuture.completedFuture("ASYNC-ORDER-" + itemId.toUpperCase());
    }
}
