package com.laker.observability.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadResourceSnapshotTest {
    @Test
    void skipsThreadInfoCountersWhenContentionSamplingIsDisabled() {
        long threadId = Thread.currentThread().getId();

        ThreadResourceSnapshot snapshot = ThreadResourceSnapshot.capture(threadId, true, false);

        assertThat(snapshot.threadId()).isEqualTo(threadId);
        assertThat(snapshot.blockedCount()).isEqualTo(-1);
        assertThat(snapshot.blockedTimeMs()).isEqualTo(-1);
        assertThat(snapshot.waitedCount()).isEqualTo(-1);
        assertThat(snapshot.waitedTimeMs()).isEqualTo(-1);
    }

    @Test
    void disabledResourceSamplingReturnsUnknownValues() {
        long threadId = Thread.currentThread().getId();

        ThreadResourceSnapshot snapshot = ThreadResourceSnapshot.capture(threadId, false, true);

        assertThat(snapshot.threadId()).isEqualTo(threadId);
        assertThat(snapshot.cpuNanos()).isEqualTo(-1);
        assertThat(snapshot.allocatedBytes()).isEqualTo(-1);
        assertThat(snapshot.blockedCount()).isEqualTo(-1);
        assertThat(snapshot.blockedTimeMs()).isEqualTo(-1);
        assertThat(snapshot.waitedCount()).isEqualTo(-1);
        assertThat(snapshot.waitedTimeMs()).isEqualTo(-1);
    }
}
