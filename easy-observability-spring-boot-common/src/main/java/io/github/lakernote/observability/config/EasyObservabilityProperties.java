package io.github.lakernote.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Runtime metrics: per-operation call statistics (HTTP endpoints, SQL, remote calls, Redis commands, jobs,
     * Kafka), which require {@code easy.observability.trace.enabled}, and JVM and pool resource metrics read from
     * Micrometer. History is kept in daily files under the storage directory.
     */
    public static class Metrics {
        private boolean enabled = true;
        /** Maximum number of distinct operations kept across all categories; later ones are merged per category. */
        private int maxOperations = 1_000;
        /** History retention in whole days, between 1 and 365 days. A bare number is read as days. */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration retention = Duration.ofDays(30);
        /** Disk budget for the metric files; the oldest days are deleted beyond it. */
        private DataSize maxStorageSize = DataSize.ofMegabytes(64);
        /**
         * Additional Micrometer meter names, or name prefixes, recorded with the resource metrics, for example
         * {@code order.created}. Values of a meter's tagged series are summed.
         */
        private List<String> include = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxOperations() { return maxOperations; }
        public void setMaxOperations(int maxOperations) { this.maxOperations = maxOperations; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }
        public DataSize getMaxStorageSize() { return maxStorageSize; }
        public void setMaxStorageSize(DataSize maxStorageSize) { this.maxStorageSize = maxStorageSize; }
        public List<String> getInclude() { return include; }
        public void setInclude(List<String> include) { this.include = include == null ? new ArrayList<>() : include; }
    }

    public static class Web {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
        /** How long stored traces are kept, in whole days between 1 and 365. A bare number is read as days. */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration retention = Duration.ofDays(7);
        /** Disk budget for the trace files; the oldest files are deleted beyond it. */
        private DataSize maxStorageSize = DataSize.ofMegabytes(128);
        /**
         * Heap budget for the newest stored traces kept in memory with their full call tree; older ones are read
         * from disk. Without usable storage, traces beyond it are dropped.
         */
        private DataSize maxMemorySize = DataSize.ofMegabytes(32);

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

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public DataSize getMaxStorageSize() {
            return maxStorageSize;
        }

        public void setMaxStorageSize(DataSize maxStorageSize) {
            this.maxStorageSize = maxStorageSize;
        }

        public DataSize getMaxMemorySize() {
            return maxMemorySize;
        }

        public void setMaxMemorySize(DataSize maxMemorySize) {
            this.maxMemorySize = maxMemorySize;
        }
    }
}
