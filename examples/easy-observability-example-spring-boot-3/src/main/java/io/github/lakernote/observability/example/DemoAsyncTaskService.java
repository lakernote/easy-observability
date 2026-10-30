package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DemoAsyncTaskService {
    private final RestClient httpBingo;
    private final Map<String, ConcurrentHashMap<String, String>> statuses = new ConcurrentHashMap<>();

    public DemoAsyncTaskService(RestClient httpBingo) {
        this.httpBingo = httpBingo;
    }

    @Async("demoTaskExecutor")
    public CompletableFuture<Map<String, Object>> executeObservedWork() {
        Map<String, Object> result = TraceBlock.call("demo.worker.process", SpanKind.CODE,
                "子线程业务处理", () -> {
                    pause(650);
                    return Map.of("workerThread", Thread.currentThread().getName(),
                            "traceId", TraceIdContext.currentTraceId(),
                            "processed", true);
                });
        return CompletableFuture.completedFuture(result);
    }

    @Async("demoTaskExecutor")
    public CompletableFuture<Map<String, String>> callRemoteOnWorker(String path) {
        String body = httpBingo.get().uri(path).retrieve().body(String.class);
        return CompletableFuture.completedFuture(Map.of(
                "workerThread", Thread.currentThread().getName(),
                "response", body == null ? "" : body.substring(0, Math.min(240, body.length()))));
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
        executeTask(taskId, mode);
        return CompletableFuture.completedFuture(null);
    }

    @Async("demoTaskExecutorUnwrapped")
    public CompletableFuture<Void> executeUnwrapped(String taskId, String mode) {
        executeTask(taskId, mode);
        return CompletableFuture.completedFuture(null);
    }

    private void executeTask(String taskId, String mode) {
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
            if ("slow".equals(mode)) pause(700);
            if ("error".equals(mode)) throw new IllegalStateException("Example executor task failure");
            state.put("status", "completed");
        } catch (RuntimeException failure) {
            state.put("status", "failed");
            state.put("cause", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
            // Keep the failure visible to the executor decorator (and the returned @Async Future).
            throw failure;
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
