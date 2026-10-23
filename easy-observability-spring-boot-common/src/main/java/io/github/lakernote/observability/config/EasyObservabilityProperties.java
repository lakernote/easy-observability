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
        private boolean httpServerObservationEnabled = true;
        private boolean redisObservationEnabled = true;
        private boolean databaseObservationEnabled = true;
        private String headerName = "X-Trace-Id";
        private long redisSlowThresholdMs = 250;
        private long jobSlowThresholdMs = 1_000;
        private long executorTaskSlowThresholdMs = 250;
        private long httpSlowThresholdMs = 250;
        private long kafkaConsumerSlowThresholdMs = 250;
        private long transactionSlowThresholdMs = 1_000;
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

        public long getTransactionSlowThresholdMs() {
            return transactionSlowThresholdMs;
        }

        public void setTransactionSlowThresholdMs(long transactionSlowThresholdMs) {
            this.transactionSlowThresholdMs = transactionSlowThresholdMs;
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
