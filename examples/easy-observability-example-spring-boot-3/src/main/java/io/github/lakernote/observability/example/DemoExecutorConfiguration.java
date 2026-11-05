package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
public class DemoExecutorConfiguration {
    @Bean("demoTaskExecutor")
    ThreadPoolTaskExecutor demoTaskExecutorWithTrace() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("demo-task-trace-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TraceContextTaskDecorator());
        return executor;
    }

    @Bean("demoTaskExecutorUnwrapped")
    ThreadPoolTaskExecutor demoTaskExecutorWithoutTrace() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("demo-task-no-trace-");
        return executor;
    }
}
