package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceIdContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class DemoExecutorController {
    private final DemoAsyncTaskService tasks;

    public DemoExecutorController(DemoAsyncTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/api/demo/executor")
    public Map<String, String> submit(@RequestParam(defaultValue = "normal") String mode,
                                     @RequestParam(defaultValue = "wrapped") String propagation) {
        if (!"normal".equals(mode) && !"slow".equals(mode) && !"error".equals(mode)) {
            throw new IllegalArgumentException("mode must be normal, slow or error");
        }
        if (!"wrapped".equals(propagation) && !"unwrapped".equals(propagation)) {
            throw new IllegalArgumentException("propagation must be wrapped or unwrapped");
        }
        String taskId = UUID.randomUUID().toString();
        tasks.registerTask(taskId, mode, propagation, TraceIdContext.currentTraceId());
        try {
            if ("wrapped".equals(propagation)) tasks.executeWrapped(taskId, mode).get(5, TimeUnit.SECONDS);
            else tasks.executeUnwrapped(taskId, mode).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Async demo task wait interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Async demo task did not finish", failure);
        }
        return tasks.status(taskId);
    }

    @GetMapping("/api/demo/executor/{taskId}")
    public Map<String, String> status(@PathVariable String taskId) {
        return tasks.status(taskId);
    }
}
