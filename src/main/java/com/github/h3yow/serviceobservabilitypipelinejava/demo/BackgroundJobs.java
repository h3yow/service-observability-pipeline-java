package com.github.h3yow.serviceobservabilitypipelinejava.demo;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.RequestIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 演示后台任务（默认关闭）：{@code observability.background-job.enabled=true} 时启用。
 *
 * <p>没有 HTTP 入口，调度线程提交时不存在请求上下文，{@link RequestIdContext#wrap(Runnable)}
 * 会赋予确定的 {@code bg-} 前缀 ID，并写入 MDC。
 */
@Component
@ConditionalOnProperty(prefix = "observability.background-job", name = "enabled", havingValue = "true")
public class BackgroundJobs {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    @Scheduled(fixedDelayString = "${observability.background-job.fixed-delay-ms:5000}")
    public void cleanupTick() {
        log.info("background cleanup tick requestId={} thread={}",
                RequestIdContext.get(), Thread.currentThread().getName());
    }
}
