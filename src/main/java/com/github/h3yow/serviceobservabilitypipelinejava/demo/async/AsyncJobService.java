package com.github.h3yow.serviceobservabilitypipelinejava.demo.async;

import java.util.concurrent.CompletableFuture;

/**
 * Request-facing surface of {@link AsyncJobs}. Declared as an interface so Spring's
 * {@code @Async} JDK proxy can be injected by type.
 */
public interface AsyncJobService {

    /** @return the correlation id observed on the async worker thread */
    CompletableFuture<String> echoCorrelationId();

    /**
     * Runs detached background work bound to the triggering request's id.
     *
     * @return the correlation id observed while the job body ran
     */
    String runBackgroundJob(String triggerCorrelationId);
}
