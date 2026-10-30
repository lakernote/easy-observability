package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Demonstrates child-thread spans and remote work propagated through the executor. */
@RestController
public class DemoAsyncFlowController {
    private final DemoAsyncTaskService tasks;
    private final RestClient httpBingo;

    public DemoAsyncFlowController(DemoAsyncTaskService tasks, RestClient httpBingo) {
        this.tasks = tasks;
        this.httpBingo = httpBingo;
    }

    @GetMapping("/api/demo/async-work")
    public Map<String, Object> asyncWork() {
        Map<String, Object> worker = await(tasks.executeObservedWork());
        return Map.of("traceId", TraceIdContext.currentTraceId(), "worker", worker);
    }

    @GetMapping("/api/demo/real-async-flow")
    public Map<String, Object> realAsyncFlow() {
        String traceId = TraceIdContext.currentTraceId();
        String workerPath = "/delay/0.55?flow=child";
        CompletableFuture<Map<String, String>> workerCall = tasks.callRemoteOnWorker(workerPath);

        String mainBody = httpBingo.get()
                .uri("/delay/0.35?flow=entry")
                .retrieve().body(String.class);
        Map<String, String> childResult = await(workerCall);

        return Map.of("traceId", traceId,
                "entryThread", Thread.currentThread().getName(),
                "entryRemoteResponse", excerpt(mainBody),
                "childWorkerThread", childResult.get("workerThread"),
                "childRemoteResponse", childResult.get("response"));
    }

    private static String excerpt(String value) {
        return value == null ? "" : value.substring(0, Math.min(240, value.length()));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Async demo wait interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Async demo did not finish", failure);
        }
    }
}
