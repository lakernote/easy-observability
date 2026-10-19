package com.laker.observability.example;

import com.laker.observability.trace.SpanKind;
import com.laker.observability.trace.TraceBlock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {
    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public String home() {
        return """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Easy Observability 示例</title><style>body{font:16px system-ui;max-width:900px;margin:48px auto;padding:0 24px;color:#172033}h1{font-size:32px}a{color:#2563eb}li{margin:12px 0}.note{color:#64748b}</style>
                <h1>Easy Observability 示例应用</h1><p>先打开监控页面，再触发几次请求生成指标、慢请求和错误记录。</p>
                <p><a href="/easy-observability/">打开监控面板 →</a></p><h2>演示请求</h2><ul>
                <li><a href="/api/demo/success">正常请求</a></li>
                <li><a href="/api/demo/slow?delayMs=900">慢请求（900 ms）</a></li>
                <li><a href="/api/demo/error">错误请求（HTTP 500）</a></li>
                <li><a href="/api/demo/trace">嵌套调用树（模拟数据库、Redis、远程调用）</a></li></ul>
                <p class="note">示例不连接真实数据库或中间件；树节点用于演示追踪 API，明确标记为模拟调用。</p></html>
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

    @GetMapping("/api/demo/error")
    public Map<String, Object> error() {
        throw new IllegalStateException("Example error for trace diagnosis");
    }

    @GetMapping("/api/demo/trace")
    public Map<String, Object> trace() {
        String value = TraceBlock.trace("customer.lookup", SpanKind.DATABASE, "simulated", () -> {
            pause(120);
            TraceBlock.trace("customer.cache", SpanKind.REDIS, "simulated", () -> pause(80));
            TraceBlock.trace("profile.remote-call", SpanKind.REMOTE, "simulated", () -> pause(120));
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
