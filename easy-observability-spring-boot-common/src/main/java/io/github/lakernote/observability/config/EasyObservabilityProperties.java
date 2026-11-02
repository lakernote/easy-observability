package io.github.lakernote.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Public configuration for the Easy Observability Spring Boot Starter.
 */
@ConfigurationProperties("easy.observability")
public class EasyObservabilityProperties {
    private boolean enabled = true;
    private final Trace trace = new Trace();
    private final Web web = new Web();
    private final Metrics metrics = new Metrics();

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

    public Metrics getMetrics() {
        return metrics;
    }

    public static class Metrics {
        private boolean enabled = true;
        private Duration step = Duration.ofSeconds(60);
        private String storageDirectory = "./data/easy-observability/metrics";
        private int retentionDays = 30;
        private DataSize maxStorageBytes = DataSize.ofMegabytes(64);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getStep() { return step; }
        public void setStep(Duration step) { this.step = step; }
        public String getStorageDirectory() { return storageDirectory; }
        public void setStorageDirectory(String storageDirectory) { this.storageDirectory = storageDirectory; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public DataSize getMaxStorageBytes() { return maxStorageBytes; }
        public void setMaxStorageBytes(DataSize maxStorageBytes) { this.maxStorageBytes = maxStorageBytes; }
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
