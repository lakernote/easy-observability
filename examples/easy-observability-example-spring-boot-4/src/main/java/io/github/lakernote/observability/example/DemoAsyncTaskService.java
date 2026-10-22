package io.github.lakernote.observability.example;

import io.github.lakernote.observability.integration.executor.ExecutorTaskTrace;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DemoAsyncTaskService {
    private final ExecutorTaskTrace observations;
    private final Map<String, ConcurrentHashMap<String, String>> statuses = new ConcurrentHashMap<>();

    public DemoAsyncTaskService(ExecutorTaskTrace observations) {
        this.observations = observations;
    }

    public void registerTask(String taskId, String mode, String propagation, String parentTraceId) {
        ConcurrentHashMap<String, String> state = new ConcurrentHashMap<>();
        state.put("taskId", taskId);
        state.put("mode", mode);
        state.put("propagation", propagation);
        state.put("parentTraceId", parentTraceId == null || parentTraceId.isBlank() ? "未创建" : parentTraceId);
        state.put("status", "queued");
        statuses.put(taskId, state);
    }

    @Async("demoTaskExecutor")
    public CompletableFuture<Void> executeWrapped(String taskId, String mode) {
        executeTask(taskId, mode, "demoTaskExecutor");
        return CompletableFuture.completedFuture(null);
    }

    @Async("demoTaskExecutorUnwrapped")
    public CompletableFuture<Void> executeUnwrapped(String taskId, String mode) {
        executeTask(taskId, mode, "demoTaskExecutorUnwrapped");
        return CompletableFuture.completedFuture(null);
    }

    private void executeTask(String taskId, String mode, String executorName) {
        ConcurrentHashMap<String, String> state = statuses.get(taskId);
        if (state == null) return;
        state.put("status", "running");
        state.put("workerThread", Thread.currentThread().getName());
        state.put("traceContextPresent", Boolean.toString(TraceContext.active()));
        String observedTraceId = TraceIdContext.currentTraceId();
        state.put("observedTraceId", observedTraceId == null || observedTraceId.isBlank() ? "未传播" : observedTraceId);
        state.put("traceIdMatchesParent", Boolean.toString(state.get("parentTraceId").equals(observedTraceId)));
        state.put("taskTraceRecorded", Boolean.toString(TraceContext.active()));
        try {
            observations.observe(executorName, "executor-demo." + mode, () -> {
                if ("slow".equals(mode)) pause(700);
                if ("error".equals(mode)) throw new IllegalStateException("Example executor task failure");
            });
            state.put("status", "completed");
        } catch (RuntimeException failure) {
            state.put("status", "failed");
            state.put("cause", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        }
    }

    public Map<String, String> status(String taskId) {
        Map<String, String> state = statuses.get(taskId);
        return state == null ? Map.of("taskId", taskId, "status", "unknown") : Map.copyOf(state);
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
