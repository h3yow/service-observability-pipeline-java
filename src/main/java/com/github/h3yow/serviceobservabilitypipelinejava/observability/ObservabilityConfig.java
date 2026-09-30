package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 可观测性装配。
 *
 * <ul>
 *   <li>{@link ResultClassifier}：注入白名单后的结果分类器；</li>
 *   <li>{@link RequestMetrics}：有界指标聚合器；</li>
 *   <li>{@link RateSampler}：可运行期调参的采样器（同时暴露为 {@link Sampler}）；</li>
 *   <li>{@code observationExecutor}：所有 {@code @Async} / 后台任务使用的线程池，
 *       通过 {@link ContextCopyingTaskDecorator} 在提交时快照请求 ID，执行时绑定、结束还原；</li>
 *   <li>{@code observationScheduler}：定时任务调度池，同样包装 Runnable；</li>
 *   <li>{@link RequestContextFilter} 注册在最前。</li>
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(ObservationProperties.class)
public class ObservabilityConfig {

    @Bean
    public ResultClassifier resultClassifier(ObservationProperties props) {
        return new ResultClassifier(new HashSet<>(props.getAllowedBusinessReasons()));
    }

    @Bean
    public RequestMetrics requestMetrics(ObservationProperties props) {
        return new RequestMetrics(props.getMetrics().getMaxSeries());
    }

    @Bean
    public RateSampler rateSampler(ObservationProperties props) {
        return new RateSampler(props.getSample().getRate());
    }

    @Bean
    public Sampler sampler(RateSampler rateSampler) {
        return rateSampler;
    }

    @Bean(name = "observationExecutor")
    public ThreadPoolTaskExecutor observationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("obs-async-");
        // 防止无上下文任务被静默拒绝丢失：拒绝时由调用线程执行（仍会包装 bg- 上下文）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Bean(name = "observationScheduler")
    public ThreadPoolTaskScheduler observationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("obs-sched-");
        scheduler.setAwaitTerminationSeconds(5);
        // 底层执行器装饰器：每次实际执行（含 Trigger 重调度）都重新包装，bg- 兜底稳定
        scheduler.setTaskDecorator(new ContextCopyingTaskDecorator());
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public FilterRegistrationBean<RequestContextFilter> requestContextFilterRegistration(
            ObservationProperties props, RequestMetrics metrics,
            ResultClassifier classifier, Sampler sampler) {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestContextFilter(props, metrics, classifier, sampler));
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** 便于在需要默认 {@link Executor} 注入的地方也拿到上下文传播执行器。 */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor(ThreadPoolTaskExecutor observationExecutor) {
        return observationExecutor;
    }
}
