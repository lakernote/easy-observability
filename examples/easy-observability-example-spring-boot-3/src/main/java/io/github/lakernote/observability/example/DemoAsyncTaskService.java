package io.github.lakernote.observability.example;

import io.github.lakernote.observability.integration.executor.ExecutorTaskTrace;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DemoAsyncTaskService {
    private final ExecutorTaskTrace observations;
    private final Map<String, Map<String, String>> statuses = new ConcurrentHashMap<>();

    public DemoAsyncTaskService(ExecutorTaskTrace observations) {
        this.observations = observations;
    }

    public void registerTask(String taskId, String mode) {
        statuses.put(taskId, Map.of("taskId", taskId, "mode", mode, "status", "queued"));
    }

    @Async("demoTaskExecutor")
    public CompletableFuture<Void> execute(String taskId, String mode) {
        statuses.put(taskId, Map.of("taskId", taskId, "mode", mode, "status", "running"));
        try {
            observations.observe("demoTaskExecutor", "executor-demo." + mode, () -> {
                if ("slow".equals(mode)) pause(700);
                if ("error".equals(mode)) throw new IllegalStateException("Example executor task failure");
            });
            statuses.put(taskId, Map.of("taskId", taskId, "mode", mode, "status", "completed"));
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            statuses.put(taskId, Map.of("taskId", taskId, "mode", mode, "status", "failed",
                    "cause", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())));
            throw failure;
        }
    }

    public Map<String, String> status(String taskId) {
        return statuses.getOrDefault(taskId, Map.of("taskId", taskId, "status", "unknown"));
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Example executor task interrupted", failure);
        }
    }
}
