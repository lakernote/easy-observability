package io.github.lakernote.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Public configuration for the Easy Observability Spring Boot Starter.
 */
@ConfigurationProperties("easy.observability")
public class EasyObservabilityProperties {
    private boolean enabled = true;
    private final Trace trace = new Trace();
    private final Web web = new Web();
    private final Metrics metrics = new Metrics();
    private final Storage storage = new Storage();

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

    public Storage getStorage() {
        return storage;
    }

    public static class Storage {
        /**
         * Directory for the trace archive and metric history. Defaults to
         * {@code ./data/easy-observability/<spring.application.name>}. Each application instance needs its own
         * directory; an instance that finds the directory in use keeps its history in memory only.
         */
        private String directory;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

    public static class Metrics {
        private boolean enabled = true;
        private Duration step = Duration.ofSeconds(60);
        /** History retention in whole days, between 7 and 365 days. A bare number is read as days. */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration retention = Duration.ofDays(30);
        private DataSize maxStorageSize = DataSize.ofMegabytes(64);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getStep() { return step; }
        public void setStep(Duration step) { this.step = step; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }
        public DataSize getMaxStorageSize() { return maxStorageSize; }
        public void setMaxStorageSize(DataSize maxStorageSize) { this.maxStorageSize = maxStorageSize; }
    }

    public static class Web {
        private boolean enabled = true;
        private int traceLimit = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
        // Thresholds of zero or below disable the corresponding trigger. A bare number is read as milliseconds.
        private Duration jobSlowThreshold = Duration.ofSeconds(1);
        private Duration httpSlowThreshold = Duration.ofMillis(500);
        private Duration kafkaConsumerSlowThreshold = Duration.ofSeconds(1);
        private Duration cpuThreshold = Duration.ofMillis(500);
        private DataSize allocatedMemoryThreshold = DataSize.ofMegabytes(50);
        private int diagnosticHistoryLimit = 1_000;
        private DataSize diagnosticMaxSize = DataSize.ofMegabytes(8);

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

        public Duration getJobSlowThreshold() {
            return jobSlowThreshold;
        }

        public void setJobSlowThreshold(Duration jobSlowThreshold) {
            this.jobSlowThreshold = jobSlowThreshold == null ? Duration.ZERO : jobSlowThreshold;
        }

        public Duration getHttpSlowThreshold() {
            return httpSlowThreshold;
        }

        public void setHttpSlowThreshold(Duration httpSlowThreshold) {
            this.httpSlowThreshold = httpSlowThreshold == null ? Duration.ZERO : httpSlowThreshold;
        }

        public Duration getKafkaConsumerSlowThreshold() {
            return kafkaConsumerSlowThreshold;
        }

        public void setKafkaConsumerSlowThreshold(Duration kafkaConsumerSlowThreshold) {
            this.kafkaConsumerSlowThreshold = kafkaConsumerSlowThreshold == null ? Duration.ZERO : kafkaConsumerSlowThreshold;
        }

        public Duration getCpuThreshold() {
            return cpuThreshold;
        }

        public void setCpuThreshold(Duration cpuThreshold) {
            this.cpuThreshold = cpuThreshold == null ? Duration.ZERO : cpuThreshold;
        }

        public DataSize getAllocatedMemoryThreshold() {
            return allocatedMemoryThreshold;
        }

        public void setAllocatedMemoryThreshold(DataSize allocatedMemoryThreshold) {
            this.allocatedMemoryThreshold = allocatedMemoryThreshold == null ? DataSize.ofBytes(0)
                    : allocatedMemoryThreshold;
        }

        public int getDiagnosticHistoryLimit() {
            return diagnosticHistoryLimit;
        }

        public void setDiagnosticHistoryLimit(int diagnosticHistoryLimit) {
            this.diagnosticHistoryLimit = diagnosticHistoryLimit;
        }

        public DataSize getDiagnosticMaxSize() {
            return diagnosticMaxSize;
        }

        public void setDiagnosticMaxSize(DataSize diagnosticMaxSize) {
            this.diagnosticMaxSize = diagnosticMaxSize;
        }

    }
}
