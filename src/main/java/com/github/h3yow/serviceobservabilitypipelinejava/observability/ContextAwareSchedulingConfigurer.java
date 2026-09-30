package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 让所有 {@code @Scheduled} 方法使用带 {@code TaskDecorator} 的 observationScheduler：
 * 后台任务无入口请求 ID，每次执行都由 {@link RequestIdContext#wrap(Runnable)} 赋予 bg- 兜底 ID。
 */
@Configuration
public class ContextAwareSchedulingConfigurer implements SchedulingConfigurer {

    private final ThreadPoolTaskScheduler scheduler;

    public ContextAwareSchedulingConfigurer(ThreadPoolTaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(scheduler);
    }
}
