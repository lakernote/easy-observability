package io.github.lakernote.observability.trace.internal.store;

import io.github.lakernote.observability.trace.internal.TraceNode;

import java.time.Instant;
import java.util.List;

/** One completed entry trace (HTTP request, scheduled job or Kafka message) with its call tree. */
public record TraceRecord(long id, String traceId, String name, String method, int status,
                          long durationMs, boolean slow, boolean error, List<TraceNode> treeNodes,
                          Instant finishedAt,
                          long threadId, long cpuTimeMs, long allocatedBytes, String sourceType,
                          String errorType, String errorMessage, String threadName, String errorStackTrace,
                          boolean highCpu, boolean highAllocation) {
    public TraceRecord {
        durationMs = Math.max(0, durationMs);
        sourceType = sourceType == null ? "" : sourceType;
        treeNodes = treeNodes == null ? List.of() : List.copyOf(treeNodes);
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        threadName = threadName == null ? "" : threadName;
        errorStackTrace = errorStackTrace == null ? "" : errorStackTrace;
    }

    public static Builder builder() {
        return new Builder();
    }

    TraceRecord withIdentity(long id, Instant finishedAt) {
        return new TraceRecord(id, traceId, name, method, status, durationMs, slow, error, treeNodes, finishedAt,
                threadId, cpuTimeMs, allocatedBytes, sourceType, errorType, errorMessage, threadName,
                errorStackTrace, highCpu, highAllocation);
    }

    /** Names every field so call sites cannot transpose the many adjacent strings, numbers and flags. */
    public static final class Builder {
        private long id;
        private String traceId;
        private String name;
        private String method;
        private int status;
        private long durationMs;
        private boolean slow;
        private boolean error;
        private List<TraceNode> treeNodes;
        private Instant finishedAt;
        private long threadId = -1;
        private long cpuTimeMs = -1;
        private long allocatedBytes = -1;
        private String sourceType;
        private String errorType;
        private String errorMessage;
        private String threadName;
        private String errorStackTrace;
        private boolean highCpu;
        private boolean highAllocation;

        private Builder() {
        }

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder slow(boolean slow) {
            this.slow = slow;
            return this;
        }

        public Builder error(boolean error) {
            this.error = error;
            return this;
        }

        public Builder treeNodes(List<TraceNode> treeNodes) {
            this.treeNodes = treeNodes;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder threadId(long threadId) {
            this.threadId = threadId;
            return this;
        }

        public Builder cpuTimeMs(long cpuTimeMs) {
            this.cpuTimeMs = cpuTimeMs;
            return this;
        }

        public Builder allocatedBytes(long allocatedBytes) {
            this.allocatedBytes = allocatedBytes;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public Builder errorStackTrace(String errorStackTrace) {
            this.errorStackTrace = errorStackTrace;
            return this;
        }

        public Builder highCpu(boolean highCpu) {
            this.highCpu = highCpu;
            return this;
        }

        public Builder highAllocation(boolean highAllocation) {
            this.highAllocation = highAllocation;
            return this;
        }

        public TraceRecord build() {
            return new TraceRecord(id, traceId, name, method, status, durationMs, slow, error,
                    treeNodes, finishedAt, threadId, cpuTimeMs, allocatedBytes, sourceType, errorType,
                    errorMessage, threadName, errorStackTrace, highCpu, highAllocation);
        }
    }
}
