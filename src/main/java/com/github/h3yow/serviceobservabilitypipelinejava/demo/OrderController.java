package com.github.h3yow.serviceobservabilitypipelinejava.demo;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.RequestIdContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 演示接口：
 * <ul>
 *   <li>POST /orders：同步下单，mode 可注入 risk/downstream/timeout；</li>
 *   <li>POST /orders/async：提交异步处理（请求 ID 跨线程池传播）；</li>
 *   <li>GET /orders/{id}：路径变量模板，用于验证指标按模板收敛。</li>
 * </ul>
 */
@RestController
public class OrderController {

    private final OrderService orderService;
    private final Executor observationExecutor;

    public OrderController(OrderService orderService, Executor observationExecutor) {
        this.orderService = orderService;
        this.observationExecutor = observationExecutor;
    }

    @PostMapping("/orders")
    public Map<String, Object> place(@Valid @RequestBody OrderRequest request) {
        String orderId = orderService.place(request);
        return Map.of(
                "orderId", orderId,
                "requestId", RequestIdContext.get(),
                "async", false);
    }

    @PostMapping("/orders/async")
    public CompletableFuture<Map<String, Object>> placeAsync(@RequestParam(defaultValue = "item-1")
                                                             String itemId) {
        // 控制器返回 CompletableFuture，Spring MVC 在 observationExecutor 上接续；
        // @Async 方法提交时快照入口请求 ID
        return orderService.processAsync(itemId)
                .thenApplyAsync(orderId -> Map.<String, Object>of(
                        "orderId", orderId,
                        "requestId", RequestIdContext.get(),
                        "async", true), observationExecutor);
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) {
        if ("0".equals(id)) {
            throw new IllegalArgumentException("id must be positive");
        }
        return Map.of("id", id, "requestId", RequestIdContext.get());
    }
}
