package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
import io.github.lakernote.observability.trace.TraceSpan;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Shows the two ways to add business steps to a trace: the annotation and a code block. */
@Service
public class DemoReportService {
    private final DemoDatabaseService database;

    public DemoReportService(DemoDatabaseService database) {
        this.database = database;
    }

    @TraceSpan(name = "ReportService.monthlyReport", kind = SpanKind.SERVICE)
    public Map<String, Object> monthlyReport(String month) {
        long orders = TraceBlock.call("report.load", SpanKind.CODE, "读取订单", database::countOrders);
        long checksum = TraceBlock.call("report.compute", SpanKind.CODE, "汇总计算", () -> DemoSupport.burnCpu(150));
        TraceBlock.run("report.render", SpanKind.CODE, "渲染 PDF", () -> DemoSupport.pause(250));
        return Map.of("month", month, "orders", orders, "checksum", checksum);
    }
}
