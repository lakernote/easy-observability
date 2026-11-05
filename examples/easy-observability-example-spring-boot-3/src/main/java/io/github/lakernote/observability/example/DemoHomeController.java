package io.github.lakernote.observability.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The demo home page: every scenario grouped by the page where its result shows up. */
@RestController
public class DemoHomeController {
    /** {@code needs} names an external dependency the case requires, or is empty. */
    private record Case(String label, String url, String needs) {
        Case(String label, String url) {
            this(label, url, "");
        }
    }

    private record Section(String title, String look, String lookUrl, String note, boolean newTab, List<Case> cases) {
    }

    private static final String REDIS = "Redis";
    private static final String KAFKA = "Kafka";
    private static final String NETWORK = "外网";

    private static final List<Section> SECTIONS = List.of(
            new Section("Trace：依赖调用", "Trace 诊断", "/easy-observability/",
                    "每个请求就是一条入口 Trace。慢（示例阈值 250 ms）和出错的会进入列表，展开看调用树和异常堆栈。",
                    false, List.of(
                    new Case("SQL 正常", "/api/demo/trace/db"),
                    new Case("SQL 慢查询（900 ms）", "/api/demo/trace/db?mode=slow"),
                    new Case("SQL 主键冲突", "/api/demo/trace/db?mode=error"),
                    new Case("事务回滚", "/api/demo/trace/transaction-rollback"),
                    new Case("N+1 查询（20 次相同 SELECT，每次 15 ms）", "/api/demo/trace/n-plus-one"),
                    new Case("自定义业务步骤（@TraceSpan + TraceBlock）", "/api/demo/trace/custom-steps"),
                    new Case("Redis 正常", "/api/demo/trace/redis", REDIS),
                    new Case("Redis 慢命令（BLPOP 1 秒）", "/api/demo/trace/redis?mode=slow", REDIS),
                    new Case("Redis WRONGTYPE 错误", "/api/demo/trace/redis?mode=error", REDIS),
                    new Case("远程 HTTP 正常", "/api/demo/trace/remote", NETWORK),
                    new Case("远程 HTTP 慢（1 秒）", "/api/demo/trace/remote?mode=slow", NETWORK),
                    new Case("远程 HTTP 500", "/api/demo/trace/remote?mode=error", NETWORK),
                    new Case("Kafka 发送并消费", "/api/demo/trace/kafka", KAFKA),
                    new Case("Kafka 慢消费", "/api/demo/trace/kafka?mode=slow", KAFKA),
                    new Case("Kafka 消费异常", "/api/demo/trace/kafka?mode=error", KAFKA),
                    new Case("完整链路：H2 → Redis → HTTP → Kafka", "/api/demo/trace/full-flow", REDIS + "、" + KAFKA + "、" + NETWORK))),
            new Section("Trace：跨线程", "Trace 诊断", "/easy-observability/",
                    "线程池配置 TraceContextTaskDecorator 后，子线程的工作挂在请求的调用树下，时间线用 T1/T2 区分线程。",
                    false, List.of(
                    new Case("等待子线程完成（650 ms）", "/api/demo/async/child-thread"),
                    new Case("请求返回后子线程仍在运行", "/api/demo/async/fire-and-forget", NETWORK),
                    new Case("带传播的线程池 · 慢任务", "/api/demo/async/executor?mode=slow"),
                    new Case("带传播的线程池 · 任务失败", "/api/demo/async/executor?mode=error"),
                    new Case("不带传播的线程池 · 对照（子线程不进 Trace）", "/api/demo/async/executor?mode=slow&propagation=unwrapped"))),
            new Section("Trace：资源超阈值", "Trace 诊断", "/easy-observability/",
                    "不慢也不报错，但入口线程 CPU 时间超过 100 ms 或内存分配超过 8 MB，同样会被记录。",
                    false, List.of(
                    new Case("高 CPU（约 200 ms 计算）", "/api/demo/resource/cpu"),
                    new Case("高内存分配（约 24 MiB）", "/api/demo/resource/allocation"))),
            new Section("实时诊断", "实时诊断", "/easy-observability/live/",
                    "这些请求会持续一段时间（默认 30 秒，可加 ?seconds=N），在新标签页打开后切到实时诊断页观察。",
                    true, List.of(
                    new Case("卡在业务步骤", "/api/demo/live/slow-step"),
                    new Case("卡在 SQL", "/api/demo/live/slow-sql"),
                    new Case("卡在远程调用（10 秒）", "/api/demo/live/slow-remote", NETWORK),
                    new Case("请求持续占满 CPU", "/api/demo/live/cpu"),
                    new Case("锁竞争：3 个线程等同一把锁", "/api/demo/live/lock"),
                    new Case("死锁（60 秒后自动解除）", "/api/demo/live/deadlock"),
                    new Case("异步请求（入口线程已释放）", "/api/demo/live/async"))),
            new Section("调用排行与指标趋势", "调用排行", "/easy-observability/stats/",
                    "批量调用本机示例接口，混合快、慢、出错和高资源请求。定时任务每 30 秒自动运行，计入“定时任务”分类。",
                    false, List.of(
                    new Case("生成 60 次调用", "/api/demo/stats/traffic"),
                    new Case("生成 300 次调用", "/api/demo/stats/traffic?requests=300"))));

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public String home() {
        StringBuilder html = new StringBuilder(8192).append("""
                <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Easy Observability 示例</title>
                <style>
                  body{font:15px/1.6 system-ui;max-width:1040px;margin:36px auto;padding:0 24px;color:#172033}
                  h1{font-size:28px;margin:0 0 6px}h2{margin:30px 0 4px;font-size:18px}
                  .pages a,.cases a{display:inline-block;margin:4px 8px 4px 0;padding:7px 11px;border:1px solid #dce3ed;
                    border-radius:7px;color:#2457b2;text-decoration:none}
                  a:hover{background:#f3f6fb}.pages{margin:16px 0;padding:12px 14px;background:#f6f8fb;border-radius:10px}
                  .note{margin:0 0 6px;color:#5b6b82;font-size:13px}.note a{color:#2457b2}
                  .needs{margin-left:6px;padding:0 5px;border-radius:4px;background:#fff4e0;color:#94600b;font-size:11px}
                  footer{margin:36px 0 0;color:#64748b;font-size:13px}code{font-size:12px}
                </style>
                <h1>Easy Observability 示例</h1>
                <p class="note">点击场景触发真实调用，再到对应页面查看效果。标了依赖的场景需要先启动对应服务。</p>
                <p class="pages"><a href="/easy-observability/">Trace 诊断</a><a href="/easy-observability/stats/">调用排行</a>
                <a href="/easy-observability/metrics/">指标趋势</a><a href="/easy-observability/live/">实时诊断</a>
                <a href="/actuator">Actuator</a></p>
                """);
        for (Section section : SECTIONS) {
            html.append("<h2>").append(section.title()).append("</h2>\n<p class=\"note\">").append(section.note())
                    .append(" 查看：<a href=\"").append(section.lookUrl()).append("\"")
                    .append(section.newTab() ? " target=\"_blank\"" : "").append(">").append(section.look())
                    .append("</a></p>\n<div class=\"cases\">");
            for (Case item : section.cases()) {
                html.append("<a href=\"").append(item.url()).append("\"")
                        .append(section.newTab() ? " target=\"_blank\"" : "").append(">").append(item.label());
                if (!item.needs().isEmpty()) html.append("<span class=\"needs\">").append(item.needs()).append("</span>");
                html.append("</a>");
            }
            html.append("</div>\n");
        }
        return html.append("""
                <footer>H2 内嵌，无需启动。Redis 和 Kafka：<code>docker compose -f examples/docker-compose.yml up -d --wait</code>；
                远程 HTTP 调用 httpbingo.org，可用 <code>easy.observability.demo.httpbingo-base-url</code> 换成兼容服务。</footer>
                </html>
                """).toString();
    }
}
