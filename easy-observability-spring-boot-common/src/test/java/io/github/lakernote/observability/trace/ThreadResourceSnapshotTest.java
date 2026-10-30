package io.github.lakernote.observability.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadResourceSnapshotTest {
    @Test
    void capturesCpuAndAllocationCounters() {
        long threadId = Thread.currentThread().getId();

        ThreadResourceSnapshot snapshot = ThreadResourceSnapshot.capture(threadId);

        assertThat(snapshot.threadId()).isEqualTo(threadId);
        assertThat(snapshot.cpuNanos()).isGreaterThanOrEqualTo(-1);
        assertThat(snapshot.allocatedBytes()).isGreaterThanOrEqualTo(-1);
    }
}
