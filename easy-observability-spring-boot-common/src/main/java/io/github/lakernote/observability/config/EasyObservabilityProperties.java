package io.github.lakernote.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Public configuration for the Easy Observability Spring Boot Starter.
 */
@ConfigurationProperties("easy.observability")
public class EasyObservabilityProperties {
    private boolean enabled = true;
    private final Trace trace = new Trace();
    private final Web web = new Web();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trace getTrace() {
        return trace;
    }

    public Web getWeb() {
        return web;
    }

    public static class Web {
        private boolean enabled = true;
        private boolean allowAnonymous;
        private int traceLimit = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAllowAnonymous() {
            return allowAnonymous;
        }

        public void setAllowAnonymous(boolean allowAnonymous) {
            this.allowAnonymous = allowAnonymous;
        }

        public int getTraceLimit() {
            return traceLimit;
        }

        public void setTraceLimit(int traceLimit) {
            this.traceLimit = traceLimit;
        }

    }

    public static class Trace {
        private boolean enabled = true;
        private String headerName = "X-Trace-Id";
        private long jobSlowThresholdMs = 1_000;
        private long httpSlowThresholdMs = 500;
        private long kafkaConsumerSlowThresholdMs = 1_000;
        private long cpuThresholdMs = 500;
        private DataSize allocatedMemoryThreshold = DataSize.ofMegabytes(50);
        private String diagnosticTraceFile = "./data/easy-observability/traces.jsonl";
        private int diagnosticHistoryLimit = 1_000;
        private DataSize diagnosticMaxBytes = DataSize.ofMegabytes(8);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName == null || headerName.isBlank() ? "X-Trace-Id" : headerName;
        }

        public long getJobSlowThresholdMs() {
            return jobSlowThresholdMs;
        }

        public void setJobSlowThresholdMs(long jobSlowThresholdMs) {
            this.jobSlowThresholdMs = jobSlowThresholdMs;
        }

        public long getHttpSlowThresholdMs() {
            return httpSlowThresholdMs;
        }

        public void setHttpSlowThresholdMs(long httpSlowThresholdMs) {
            this.httpSlowThresholdMs = httpSlowThresholdMs;
        }

        public long getKafkaConsumerSlowThresholdMs() {
            return kafkaConsumerSlowThresholdMs;
        }

        public void setKafkaConsumerSlowThresholdMs(long kafkaConsumerSlowThresholdMs) {
            this.kafkaConsumerSlowThresholdMs = kafkaConsumerSlowThresholdMs;
        }

        public long getCpuThresholdMs() {
            return cpuThresholdMs;
        }

        public void setCpuThresholdMs(long cpuThresholdMs) {
            this.cpuThresholdMs = cpuThresholdMs;
        }

        public DataSize getAllocatedMemoryThreshold() {
            return allocatedMemoryThreshold;
        }

        public void setAllocatedMemoryThreshold(DataSize allocatedMemoryThreshold) {
            this.allocatedMemoryThreshold = allocatedMemoryThreshold;
        }

        public String getDiagnosticTraceFile() {
            return diagnosticTraceFile;
        }

        public void setDiagnosticTraceFile(String diagnosticTraceFile) {
            this.diagnosticTraceFile = diagnosticTraceFile;
        }

        public int getDiagnosticHistoryLimit() {
            return diagnosticHistoryLimit;
        }

        public void setDiagnosticHistoryLimit(int diagnosticHistoryLimit) {
            this.diagnosticHistoryLimit = diagnosticHistoryLimit;
        }

        public DataSize getDiagnosticMaxBytes() {
            return diagnosticMaxBytes;
        }

        public void setDiagnosticMaxBytes(DataSize diagnosticMaxBytes) {
            this.diagnosticMaxBytes = diagnosticMaxBytes;
        }

    }
}
