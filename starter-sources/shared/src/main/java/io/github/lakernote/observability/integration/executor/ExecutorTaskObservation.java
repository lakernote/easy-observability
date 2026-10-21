package io.github.lakernote.observability.integration.executor;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Objects;
import java.util.function.Supplier;

/** Records one executor task as an independent observable entry, including duration and failures. */
public final class ExecutorTaskObservation {
    public static final String OBSERVATION_NAME = "tasks.executor.execution";
    private final ObservationRegistry registry;

    public ExecutorTaskObservation(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void observe(String executorName, String taskName, Runnable task) {
        Objects.requireNonNull(task, "task");
        observation(executorName, taskName).observe(task);
    }

    public <T> T observe(String executorName, String taskName, Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        return observation(executorName, taskName).observe(task);
    }

    private Observation observation(String executorName, String taskName) {
        return Observation.createNotStarted(OBSERVATION_NAME, registry)
                .contextualName(safeName(taskName, "executor-task"))
                .lowCardinalityKeyValue("executor.name", safeName(executorName, "unknown"));
    }

    private static String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 200 ? clean : clean.substring(0, 200);
    }
}
