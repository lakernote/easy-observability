package com.laker.observability.example;

import com.laker.observability.trace.SpanKind;
import com.laker.observability.trace.TraceBlock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {
    private static volatile long cpuResult;

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public String home() {
        return """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Easy Observability 示例</title><style>body{font:16px system-ui;max-width:900px;margin:48px auto;padding:0 24px;color:#172033}h1{font-size:32px}a{color:#2563eb}li{margin:12px 0}.note{color:#64748b}</style>
                <h1>Easy Observability 示例应用</h1><p>先打开监控页面，再触发几次请求生成指标、慢请求和错误记录。</p>
                <p><a href="/easy-observability/">打开监控面板 →</a></p><h2>演示请求</h2><ul>
                <li><a href="/api/demo/success">正常请求</a></li>
                <li><a href="/api/demo/slow?delayMs=900">慢请求（900 ms）</a></li>
                <li><a href="/api/demo/high-cpu?durationMs=900">高 CPU 请求（约 900 ms）</a></li>
                <li><a href="/api/demo/high-memory?sizeMb=24">高内存分配请求（24 MiB）</a></li>
                <li><a href="/api/demo/error">错误请求（HTTP 500）</a></li>
                <li><a href="/api/demo/trace">嵌套调用树（模拟调用）</a></li>
                <li><a href="/api/demo/redis">真实 Redis 读写</a></li>
                <li><a href="/api/demo/remote">真实远程调用（httpbingo，含 1 秒延迟）</a></li>
                <li><a href="/api/demo/kafka">Kafka 正常消费并验证 Trace ID</a></li>
                <li><a href="/api/demo/kafka/slow">Kafka 慢消费（约 650 ms）</a></li>
                <li><a href="/api/demo/kafka/error">Kafka 消费异常（验证错误 Trace）</a></li>
                <li><a href="/api/demo/kafka/status">查看最近消费的 Trace ID</a></li></ul>
                <p class="note">真实 Redis 和 Kafka 示例需要先启动 examples/docker-compose.yml 中的依赖。定时任务每 30 秒自动执行一次 Redis 计数。</p></html>
                """;
    }

    @GetMapping("/api/demo/success")
    public Map<String, Object> success() {
        return Map.of("result", "ok", "message", "Normal request recorded");
    }

    @GetMapping("/api/demo/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "900") long delayMs) throws InterruptedException {
        long boundedDelay = Math.max(0, Math.min(delayMs, 5000));
        Thread.sleep(boundedDelay);
        return Map.of("result", "ok", "delayMs", boundedDelay);
    }

    @GetMapping("/api/demo/high-cpu")
    public Map<String, Object> highCpu(@RequestParam(defaultValue = "900") long durationMs) {
        long boundedDuration = Math.max(100, Math.min(durationMs, 3000));
        long deadline = System.nanoTime() + boundedDuration * 1_000_000L;
        long value = 1;
        long iterations = 0;
        while (System.nanoTime() < deadline) {
            value = (value * 2_862_933_555_777_941_757L + 3_037_000_493L) ^ (value >>> 17);
            iterations++;
        }
        cpuResult = value;
        return Map.of("result", "ok", "durationMs", boundedDuration, "iterations", iterations);
    }

    @GetMapping("/api/demo/high-memory")
    public Map<String, Object> highMemory(@RequestParam(defaultValue = "24") int sizeMb) {
        int boundedSizeMb = Math.max(8, Math.min(sizeMb, 32));
        byte[][] blocks = new byte[boundedSizeMb][];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new byte[1024 * 1024];
            for (int offset = 0; offset < blocks[i].length; offset += 4096) {
                blocks[i][offset] = (byte) (i + offset);
            }
        }
        pause(300); // 让示例超过默认慢请求阈值，便于在慢请求列表中下钻查看分配量。
        long checksum = 0;
        for (byte[] block : blocks) checksum += block[block.length - 4096];
        return Map.of("result", "ok", "allocatedMb", boundedSizeMb, "checksum", checksum);
    }

    @GetMapping("/api/demo/error")
    public Map<String, Object> error() {
        throw new IllegalStateException("Example error for trace diagnosis");
    }

    @GetMapping("/api/demo/trace")
    public Map<String, Object> trace() {
        String value = TraceBlock.call("customer.lookup", SpanKind.DATABASE, "simulated", () -> {
            pause(120);
            TraceBlock.run("customer.cache", SpanKind.REDIS, "simulated", () -> pause(80));
            TraceBlock.run("profile.remote-call", SpanKind.REMOTE, "simulated", () -> pause(120));
            return "customer-1001";
        });
        return Map.of("result", "ok", "customer", value, "note", "database/Redis/remote nodes are simulated");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Example call interrupted", e);
        }
    }
}
