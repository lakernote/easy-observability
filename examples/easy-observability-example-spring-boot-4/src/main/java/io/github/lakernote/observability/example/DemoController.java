package io.github.lakernote.observability.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {
    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public String home() {
        return """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Easy Observability Trace 示例</title>
                <style>
                  body{font:16px system-ui;max-width:980px;margin:40px auto;padding:0 24px;color:#172033}
                  h1{font-size:30px}h2{margin:26px 0 10px;font-size:19px}
                  a{display:inline-block;margin:5px 8px 5px 0;padding:8px 11px;border:1px solid #dce3ed;border-radius:7px;color:#2457b2;text-decoration:none}
                  a:hover{background:#f3f6fb}.note{color:#64748b;font-size:14px;line-height:1.6}
                  .toolbar{margin:18px 0;padding:14px 16px;background:#f6f8fb;border-radius:10px}
                  code{font-size:13px}
                </style>
                <h1>Easy Observability Trace 示例</h1>
                <p>按场景触发真实依赖调用，打开监控页查看 Trace 耗时、资源告警、异常堆栈和线程资源。</p>
                <p class="toolbar"><a href="/easy-observability/">打开 Trace 监控页 →</a><a href="/actuator">Actuator 端点</a></p>

                <h2>JDBC · H2</h2>
                <a href="/api/demo/db?mode=normal">正常查询</a>
                <a href="/api/demo/db?mode=slow">模拟慢查询</a>
                <a href="/api/demo/db?mode=error">主键冲突错误</a>

                <h2>Redis</h2>
                <a href="/api/demo/redis?mode=normal">正常读写</a>
                <a href="/api/demo/redis?mode=slow">慢命令（阻塞等待）</a>
                <a href="/api/demo/redis?mode=error">错误命令（WRONGTYPE）</a>

                <h2>远程 HTTP · httpbingo</h2>
                <a href="/api/demo/remote?mode=normal">正常调用</a>
                <a href="/api/demo/remote?mode=slow">慢调用（约 1 秒）</a>
                <a href="/api/demo/remote?mode=error">HTTP 500</a>

                <h2>Kafka · producer → consumer</h2>
                <a href="/api/demo/kafka">正常消费</a>
                <a href="/api/demo/kafka/slow">慢消费</a>
                <a href="/api/demo/kafka/error">消费异常</a>

                <h2>异步线程池上下文传播对照</h2>
                <p class="note">每个请求会等待异步任务完成，以便父 Trace 保持打开。带包装器时任务沿用当前 Trace；不带包装器时任务无法关联到请求 Trace。</p>
                <a href="/api/demo/executor?mode=normal&propagation=wrapped">带包装器 · 正常</a>
                <a href="/api/demo/executor?mode=slow&propagation=wrapped">带包装器 · 慢任务</a>
                <a href="/api/demo/executor?mode=error&propagation=wrapped">带包装器 · 失败任务</a><br>
                <a href="/api/demo/executor?mode=normal&propagation=unwrapped">不带包装器 · 正常</a>
                <a href="/api/demo/executor?mode=slow&propagation=unwrapped">不带包装器 · 慢任务</a>
                <a href="/api/demo/executor?mode=error&propagation=unwrapped">不带包装器 · 失败任务</a>

                <h2>子线程与并行远程调用</h2>
                <p class="note">子线程会继承入口 Trace。打开 Trace 详情，可查看 executor.task 子节点、工作线程，以及子线程发起的远程 HTTP Span。</p>
                <a href="/api/demo/async-work">子线程处理任务（约 650 ms）</a>
                <a href="/api/demo/real-async-flow">主线程 + 子线程并行远程调用</a>

                <h2>完整业务链路</h2>
                <a href="/api/demo/real-flow">H2 → Redis → HTTP → Kafka producer</a>

                <h2>入口线程资源阈值</h2>
                <a href="/api/demo/resources/cpu">高 CPU（约 200 ms 计算）</a>
                <a href="/api/demo/resources/allocation">高内存分配（约 24 MiB）</a>

                <p class="note">H2 内嵌数据库无需额外启动。Redis 和 Kafka 场景需要先启动 <code>examples/docker-compose.yml</code>；远程 HTTP 场景需要可访问 httpbingo。慢和错误用例是有界的本地诊断演示。</p>
                </html>
                """;
    }
}
