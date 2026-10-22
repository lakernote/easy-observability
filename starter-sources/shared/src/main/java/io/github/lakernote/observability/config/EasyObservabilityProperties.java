package io.github.lakernote.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Public configuration for the Easy Observability Spring Boot Starter.
 */
@ConfigurationProperties("easy.observability")
public class EasyObservabilityProperties {
    private boolean enabled = true;
    private final Metrics metrics = new Metrics();
    private final Trace trace = new Trace();
    private final Web web = new Web();
    private final Redis redis = new Redis();
    private final Transaction transaction = new Transaction();
    private final Http http = new Http();
    private final Kafka kafka = new Kafka();
    private final Mybatis mybatis = new Mybatis();
    private final Scheduling scheduling = new Scheduling();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Trace getTrace() {
        return trace;
    }

    public Web getWeb() {
        return web;
    }

    public Redis getRedis() {
        return redis;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public Http getHttp() {
        return http;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Mybatis getMybatis() {
        return mybatis;
    }

    public Scheduling getScheduling() {
        return scheduling;
    }

    public static class Scheduling {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mybatis {
        private boolean enabled = true;
        private long slowThresholdMs = 250;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }
    }

    public static class Kafka {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Transaction {
        private boolean enabled = true;
        private long slowThresholdMs = 1_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }
    }

    public static class Http {
        private final OkHttp okhttp = new OkHttp();

        public OkHttp getOkhttp() {
            return okhttp;
        }

        public static class OkHttp {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }

    public static class Redis {
        private boolean enabled = true;
        private String serviceName = "easy-observability";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }
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

    public static class Metrics {
        private final History history = new History();

        public History getHistory() {
            return history;
        }

        public static class History {
            private boolean enabled = true;
            private String storageDir = "./data/easy-observability/metrics";
            private int retentionDays = 15;
            private DataSize maxStorageBytes = DataSize.ofMegabytes(64);
            private DataSize maxSeriesBytes = DataSize.ofMegabytes(8);

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getStorageDir() {
                return storageDir;
            }

            public void setStorageDir(String storageDir) {
                this.storageDir = storageDir;
            }

            public int getRetentionDays() {
                return retentionDays;
            }

            public void setRetentionDays(int retentionDays) {
                this.retentionDays = retentionDays;
            }

            public DataSize getMaxStorageBytes() {
                return maxStorageBytes;
            }

            public void setMaxStorageBytes(DataSize maxStorageBytes) {
                this.maxStorageBytes = maxStorageBytes;
            }

            public DataSize getMaxSeriesBytes() {
                return maxSeriesBytes;
            }

            public void setMaxSeriesBytes(DataSize maxSeriesBytes) {
                this.maxSeriesBytes = maxSeriesBytes;
            }

        }

    }

    public static class Trace {
        private boolean enabled = true;
        private boolean httpServerObservationEnabled = true;
        private boolean redisObservationEnabled = true;
        private boolean databaseObservationEnabled = true;
        private String headerName = "X-Trace-Id";
        private long redisSlowThresholdMs = 250;
        private long jobSlowThresholdMs = 1_000;
        private long executorTaskSlowThresholdMs = 250;
        private long httpSlowThresholdMs = 250;
        private long kafkaConsumerSlowThresholdMs = 250;
        private long dependencySlowThresholdMs = 250;
        // CPU and allocation counters require JVM management calls for every observed operation; keep opt-in.
        private boolean resourceSamplingEnabled;
        private String slowErrorTraceFile = "./data/easy-observability/traces.jsonl";
        private int slowErrorHistoryLimit = 1_000;
        private DataSize slowErrorMaxBytes = DataSize.ofMegabytes(8);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isHttpServerObservationEnabled() {
            return httpServerObservationEnabled;
        }

        public void setHttpServerObservationEnabled(boolean enabled) {
            this.httpServerObservationEnabled = enabled;
        }

        public boolean isRedisObservationEnabled() {
            return redisObservationEnabled;
        }

        public void setRedisObservationEnabled(boolean enabled) {
            this.redisObservationEnabled = enabled;
        }

        public boolean isDatabaseObservationEnabled() {
            return databaseObservationEnabled;
        }

        public void setDatabaseObservationEnabled(boolean enabled) {
            this.databaseObservationEnabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName == null || headerName.isBlank() ? "X-Trace-Id" : headerName;
        }

        public long getRedisSlowThresholdMs() {
            return redisSlowThresholdMs;
        }

        public void setRedisSlowThresholdMs(long redisSlowThresholdMs) {
            this.redisSlowThresholdMs = redisSlowThresholdMs;
        }

        public long getJobSlowThresholdMs() {
            return jobSlowThresholdMs;
        }

        public void setJobSlowThresholdMs(long jobSlowThresholdMs) {
            this.jobSlowThresholdMs = jobSlowThresholdMs;
        }

        public long getExecutorTaskSlowThresholdMs() {
            return executorTaskSlowThresholdMs;
        }

        public void setExecutorTaskSlowThresholdMs(long thresholdMs) {
            this.executorTaskSlowThresholdMs = thresholdMs;
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

        public long getDependencySlowThresholdMs() {
            return dependencySlowThresholdMs;
        }

        public void setDependencySlowThresholdMs(long dependencySlowThresholdMs) {
            this.dependencySlowThresholdMs = dependencySlowThresholdMs;
        }

        public boolean isResourceSamplingEnabled() {
            return resourceSamplingEnabled;
        }

        public void setResourceSamplingEnabled(boolean resourceSamplingEnabled) {
            this.resourceSamplingEnabled = resourceSamplingEnabled;
        }

        public String getSlowErrorTraceFile() {
            return slowErrorTraceFile;
        }

        public void setSlowErrorTraceFile(String slowErrorTraceFile) {
            this.slowErrorTraceFile = slowErrorTraceFile;
        }

        public int getSlowErrorHistoryLimit() {
            return slowErrorHistoryLimit;
        }

        public void setSlowErrorHistoryLimit(int slowErrorHistoryLimit) {
            this.slowErrorHistoryLimit = slowErrorHistoryLimit;
        }

        public DataSize getSlowErrorMaxBytes() {
            return slowErrorMaxBytes;
        }

        public void setSlowErrorMaxBytes(DataSize slowErrorMaxBytes) {
            this.slowErrorMaxBytes = slowErrorMaxBytes;
        }
    }
}
