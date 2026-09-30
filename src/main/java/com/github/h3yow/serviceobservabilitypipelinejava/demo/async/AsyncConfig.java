package com.github.h3yow.serviceobservabilitypipelinejava.demo.async;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Defines the {@code obsAsyncExecutor} used by {@link AsyncJobs}. The
 * {@link CorrelationTaskDecorator} is applied by the executor so every task submitted
 * from a request thread carries that request's id onto the worker thread, and a
 * missing id (self-scheduled work) gets an {@code auto-} fallback.
 *
 * <p>The pool is bounded and uses CallerRunsPolicy as back-pressure: under overload
 * the caller runs the task, keeping resource use (and memory) bounded.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String EXECUTOR_NAME = "obsAsyncExecutor";

    @Bean
    public CorrelationTaskDecorator correlationTaskDecorator() {
        return new CorrelationTaskDecorator();
    }

    @Bean(name = EXECUTOR_NAME)
    public Executor obsAsyncExecutor(CorrelationTaskDecorator decorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("obs-async-");
        executor.setTaskDecorator(decorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
