package io.github.lakernote.meerkat.example;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Fills the 调用排行 and 指标趋势 pages with a realistic mix of fast, slow, failing and heavy requests. */
@RestController
@RequestMapping("/api/demo/stats")
public class StatsDemoController {
    /** Weighted by repetition: mostly fast calls, some slow, failing and resource-heavy ones. */
    private static final List<String> MIX = List.of(
            "/api/demo/trace/db", "/api/demo/trace/db", "/api/demo/trace/db", "/api/demo/trace/db",
            "/api/demo/trace/n-plus-one", "/api/demo/trace/n-plus-one",
            "/api/demo/trace/custom-steps",
            "/api/demo/trace/db?mode=slow",
            "/api/demo/trace/db?mode=error",
            "/api/demo/trace/transaction-rollback",
            "/api/demo/resource/cpu",
            "/api/demo/resource/allocation", "/api/demo/resource/allocation",
            "/api/demo/async/executor", "/api/demo/async/executor?mode=error");
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Environment environment;

    public StatsDemoController(Environment environment) {
        this.environment = environment;
    }

    /** Sends {@code requests} calls (at most 500) from 4 threads to local demo endpoints and counts the outcomes. */
    @GetMapping("/traffic")
    public Map<String, Object> traffic(@RequestParam(defaultValue = "60") int requests) throws Exception {
        int total = Math.max(1, Math.min(500, requests));
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        Map<String, AtomicInteger> statuses = new ConcurrentHashMap<>();
        AtomicInteger next = new AtomicInteger();
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> workers = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                workers.add(pool.submit(() -> {
                    for (int index = next.getAndIncrement(); index < total; index = next.getAndIncrement()) {
                        String path = MIX.get(index % MIX.size());
                        statuses.computeIfAbsent(path + " → " + send(base + path), key -> new AtomicInteger())
                                .incrementAndGet();
                    }
                }));
            }
            for (Future<?> worker : workers) worker.get();
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> outcome = new TreeMap<>();
        statuses.forEach((key, count) -> outcome.put(key, count.get()));
        return Map.of("requests", total, "elapsedMs", Duration.ofNanos(System.nanoTime() - started).toMillis(),
                "outcomes", outcome, "next", "打开 /meerkat/stats/ 查看调用排行");
    }

    private static String send(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            return String.valueOf(CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception failure) {
            return failure.getClass().getSimpleName();
        }
    }
}
