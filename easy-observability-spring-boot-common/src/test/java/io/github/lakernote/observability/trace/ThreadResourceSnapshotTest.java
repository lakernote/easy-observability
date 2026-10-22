package io.github.lakernote.observability.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadResourceSnapshotTest {
    @Test
    void capturesCpuAndAllocationCounters() {
        long threadId = Thread.currentThread().getId();

        ThreadResourceSnapshot snapshot = ThreadResourceSnapshot.capture(threadId, true);

        assertThat(snapshot.threadId()).isEqualTo(threadId);
        assertThat(snapshot.cpuNanos()).isGreaterThanOrEqualTo(-1);
        assertThat(snapshot.allocatedBytes()).isGreaterThanOrEqualTo(-1);
    }

    @Test
    void disabledResourceSamplingReturnsUnknownValues() {
        long threadId = Thread.currentThread().getId();

        ThreadResourceSnapshot snapshot = ThreadResourceSnapshot.capture(threadId, false);

        assertThat(snapshot.threadId()).isEqualTo(threadId);
        assertThat(snapshot.cpuNanos()).isEqualTo(-1);
        assertThat(snapshot.allocatedBytes()).isEqualTo(-1);
    }
}
