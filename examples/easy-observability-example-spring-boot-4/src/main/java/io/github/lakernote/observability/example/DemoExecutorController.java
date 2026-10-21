package io.github.lakernote.observability.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class DemoExecutorController {
    private final DemoAsyncTaskService tasks;

    public DemoExecutorController(DemoAsyncTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/api/demo/executor")
    public Map<String, String> submit(@RequestParam(defaultValue = "normal") String mode) {
        if (!"normal".equals(mode) && !"slow".equals(mode) && !"error".equals(mode)) {
            throw new IllegalArgumentException("mode must be normal, slow or error");
        }
        String taskId = UUID.randomUUID().toString();
        tasks.registerTask(taskId, mode);
        tasks.execute(taskId, mode);
        return tasks.status(taskId);
    }

    @GetMapping("/api/demo/executor/{taskId}")
    public Map<String, String> status(@PathVariable String taskId) {
        return tasks.status(taskId);
    }
}
