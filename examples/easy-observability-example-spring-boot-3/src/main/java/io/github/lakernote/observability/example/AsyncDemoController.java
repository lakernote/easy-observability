package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Trace across threads: waited child work, work that outlives the request, and executors with or without propagation. */
@RestController
@RequestMapping("/api/demo/async")
public class AsyncDemoController {
    private final DemoAsyncTaskService tasks;
    private final RestClient httpBingo;

    public AsyncDemoController(DemoAsyncTaskService tasks, RestClient httpBingo) {
        this.tasks = tasks;
        this.httpBingo = httpBingo;
    }

    /** The request waits for a 650 ms task on a pool thread; the task appears under the request as T2. */
    @GetMapping("/child-thread")
    public Map<String, Object> childThread() {
        return Map.of("traceId", TraceIdContext.currentTraceId(), "worker", DemoSupport.await(tasks.processOnWorker()));
    }

    /** The request returns while its pool task is still running; the task is marked "请求结束时仍运行". */
    @GetMapping("/fire-and-forget")
    public Map<String, Object> fireAndForget() {
        tasks.callRemoteAndLinger("/anything/child?flow=fire-and-forget");
        String body = httpBingo.get().uri("/anything/entry?flow=fire-and-forget").retrieve().body(String.class);
        return Map.of("traceId", TraceIdContext.currentTraceId(), "entryRemoteResponse", DemoSupport.excerpt(body),
                "childRemoteCall", "submitted without waiting");
    }

    /**
     * Runs a task on a pool with {@code TraceContextTaskDecorator} (wrapped) or without it (unwrapped). Only the
     * wrapped task joins the request trace; an error task fails the request.
     */
    @GetMapping("/executor")
    public Map<String, Object> executor(@RequestParam(defaultValue = "normal") String mode,
                                        @RequestParam(defaultValue = "wrapped") String propagation) {
        DemoSupport.requireMode(mode);
        if (!"wrapped".equals(propagation) && !"unwrapped".equals(propagation)) {
            throw new IllegalArgumentException("propagation must be wrapped or unwrapped");
        }
        String traceId = TraceIdContext.currentTraceId();
        Map<String, Object> worker = DemoSupport.await("wrapped".equals(propagation)
                ? tasks.runWrapped(mode) : tasks.runUnwrapped(mode));
        Map<String, Object> result = new LinkedHashMap<>(worker);
        result.put("propagation", propagation);
        result.put("requestTraceId", traceId);
        result.put("propagated", traceId.equals(worker.get("workerTraceId")));
        return result;
    }
}
