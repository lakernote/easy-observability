package io.github.lakernote.meerkat.example;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.TraceBlock;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class DemoAsyncTaskService {
    private final RestClient httpBingo;

    public DemoAsyncTaskService(RestClient httpBingo) {
        this.httpBingo = httpBingo;
    }

    @Async("demoTaskExecutor")
    public CompletableFuture<Map<String, Object>> processOnWorker() {
        return CompletableFuture.completedFuture(TraceBlock.call("demo.worker.process", SpanKind.CODE, "子线程业务处理",
                () -> {
                    DemoSupport.pause(650);
                    return worker("completed");
                }));
    }

    /** Calls a remote service and keeps working for 4 s, so it outlives the request that started it. */
    @Async("demoTaskExecutor")
    public void callRemoteAndLinger(String path) {
        httpBingo.get().uri(path).retrieve().toBodilessEntity();
        DemoSupport.pause(4_000);
    }

    @Async("demoTaskExecutor")
    public CompletableFuture<Map<String, Object>> runWrapped(String mode) {
        return CompletableFuture.completedFuture(task(mode));
    }

    @Async("demoTaskExecutorUnwrapped")
    public CompletableFuture<Map<String, Object>> runUnwrapped(String mode) {
        return CompletableFuture.completedFuture(task(mode));
    }

    private static Map<String, Object> task(String mode) {
        if ("slow".equals(mode)) DemoSupport.pause(700);
        if ("error".equals(mode)) throw new IllegalStateException("Example executor task failure");
        return worker(mode);
    }

    private static Map<String, Object> worker(String outcome) {
        String traceId = TraceIdContext.currentTraceId();
        return Map.of("workerThread", Thread.currentThread().getName(),
                "workerTraceId", traceId == null || traceId.isBlank() ? "未传播" : traceId, "outcome", outcome);
    }
}
