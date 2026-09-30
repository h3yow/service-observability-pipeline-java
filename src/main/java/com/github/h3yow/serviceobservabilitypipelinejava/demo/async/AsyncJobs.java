package com.github.h3yow.serviceobservabilitypipelinejava.demo.async;

import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Async / scheduled work used to prove correlation-id propagation off the request
 * thread.
 *
 * <ul>
 *   <li>{@link #echoCorrelationId()} runs on {@code obsAsyncExecutor}; the executor's
 *       task decorator re-binds the submitting request's id.</li>
 *   <li>{@link #runBackgroundJob(String)} models a request that enqueues detached
 *       background work: the trigger id is bound explicitly for the job's duration.</li>
 *   <li>{@link #scheduledHeartbeat()} is a self-triggered scheduled job with no
 *       request; it wraps itself in a generated {@code auto-} id so its logs are never
 *       anonymous. It is registered programmatically with a long, non-test-blocking
 *       period.</li>
 * </ul>
 */
@Component
public class AsyncJobs implements AsyncJobService, SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobs.class);

    @Async(AsyncConfig.EXECUTOR_NAME)
    public CompletableFuture<String> echoCorrelationId() {
        String id = CorrelationContext.current();
        log.info("async task running with correlationId={}", id);
        return CompletableFuture.completedFuture(id);
    }

    /**
     * Runs detached work under the id of the request that triggered it.
     *
     * @return the correlation id observed while the job body ran
     */
    public String runBackgroundJob(String triggerCorrelationId) {
        String[] observed = new String[1];
        CorrelationContext.runWith(triggerCorrelationId, () -> {
            observed[0] = CorrelationContext.current();
            log.info("background job triggered by request correlationId={}", observed[0]);
        });
        return observed[0];
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(buildScheduler());
        registrar.addTriggerTask(this::scheduledHeartbeat,
                new PeriodicTrigger(60, TimeUnit.SECONDS));
    }

    private void scheduledHeartbeat() {
        CorrelationContext.runWithGenerated(() ->
                log.info("scheduled heartbeat correlationId={}", CorrelationContext.current()));
    }

    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler buildScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("obs-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
