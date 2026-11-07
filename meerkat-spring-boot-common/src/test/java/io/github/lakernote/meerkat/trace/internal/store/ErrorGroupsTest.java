package io.github.lakernote.meerkat.trace.internal.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorGroupsTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    void groupsFailuresByTypeAndMaskedMessage() {
        TraceStore store = store();
        add(store, "t1", "GET /orders/{id}", "OrderNotFound", "order 17 not found", 50);
        add(store, "t2", "GET /orders/{id}", "OrderNotFound", "order 4211 not found", 40);
        add(store, "t3", "POST /orders", "OrderNotFound", "order 9 not found", 30);
        add(store, "t4", "POST /pay", "java.sql.SQLTimeoutException", "timeout after 3000 ms", 20);
        add(store, "t5", "GET /health", "HTTP_500", "HTTP status 500", 10);
        add(store, "t6", "GET /other", "HTTP_500", "HTTP status 500", 5);
        CLOCK.at = NOW.minus(Duration.ofMinutes(1));
        store.add(record("ok", "GET /fine", "", "", 1).error(false).slow(true).build());

        ErrorGroups.Report report = ErrorGroups.compute(store, NOW, Duration.ofHours(1), "", 50);

        assertThat(report.failures()).isEqualTo(6);
        assertThat(report.total()).isEqualTo(4);
        ErrorGroups.Group top = report.groups().get(0);
        assertThat(top.errorType()).isEqualTo("OrderNotFound");
        assertThat(top.message()).isEqualTo("order {n} not found");
        assertThat(top.count()).isEqualTo(3);
        assertThat(top.latestTraceId()).isEqualTo("t3");
        assertThat(top.sampleMessage()).isEqualTo("order 9 not found");
        assertThat(top.firstSeen()).isEqualTo(NOW.minus(Duration.ofMinutes(50)));
        assertThat(top.lastSeen()).isEqualTo(NOW.minus(Duration.ofMinutes(30)));
        assertThat(top.newInWindow()).isTrue();
        assertThat(top.entries()).extracting(ErrorGroups.EntryCount::name)
                .containsExactly("GET /orders/{id}", "POST /orders");
        assertThat(top.entries().get(0).count()).isEqualTo(2);
        assertThat(top.trend()).hasSize(24);
        assertThat(java.util.Arrays.stream(top.trend()).sum()).isEqualTo(3);
        // Bare status failures are split by entry because they carry no exception to group by.
        assertThat(report.groups()).filteredOn(group -> group.errorType().equals("HTTP_500")).hasSize(2);
    }

    @Test
    void honoursWindowSourceAndLimitAndFlagsRecurringGroups() {
        TraceStore store = store();
        add(store, "old", "GET /a", "Boom", "boom", 60 * 10);
        add(store, "new", "GET /a", "Boom", "boom", 10);
        add(store, "job", "nightly", "JobFailed", "failed", 5, "JOB");

        ErrorGroups.Report hour = ErrorGroups.compute(store, NOW, Duration.ofHours(1), "", 50);
        assertThat(hour.groups()).extracting(ErrorGroups.Group::errorType).containsExactlyInAnyOrder("Boom", "JobFailed");
        assertThat(hour.groups()).filteredOn(group -> group.errorType().equals("Boom"))
                .singleElement().satisfies(group -> {
                    assertThat(group.count()).isEqualTo(1);
                    assertThat(group.newInWindow()).isFalse();
                });

        assertThat(ErrorGroups.compute(store, NOW, Duration.ofHours(1), "JOB", 50).groups())
                .extracting(ErrorGroups.Group::errorType).containsExactly("JobFailed");
        assertThat(ErrorGroups.compute(store, NOW, Duration.ofDays(1), "", 1).groups()).hasSize(1)
                .first().satisfies(group -> assertThat(group.count()).isEqualTo(2));
        assertThat(ErrorGroups.compute(TraceStore.inMemory(1024), NOW, Duration.ofHours(1), "", 50).groups()).isEmpty();
    }

    @Test
    void masksVariablePartsOfMessages() {
        assertThat(ErrorGroups.template(null)).isEmpty();
        assertThat(ErrorGroups.template("  ")).isEmpty();
        assertThat(ErrorGroups.template("user 3f2a1b9c-1111-2222-3333-444455556666 missing"))
                .isEqualTo("user {id} missing");
        assertThat(ErrorGroups.template("duplicate key 'abc@x.com' for \"users\""))
                .isEqualTo("duplicate key '…' for '…'");
        assertThat(ErrorGroups.template("object com.x.Foo@1a2b3c4d at 0xdeadbeef token a1b2c3d4e5f6"))
                .isEqualTo("object com.x.Foo@{hash} at {id} token {id}");
        assertThat(ErrorGroups.template("took 12.5s\nsecond line")).isEqualTo("took {n}s");
        assertThat(ErrorGroups.template("x".repeat(500))).hasSize(201).endsWith("…");
        assertThat(ErrorGroups.template("deadline exceeded")).isEqualTo("deadline exceeded");
    }

    private static void add(TraceStore store, String id, String name, String type, String message, long minutesAgo) {
        add(store, id, name, type, message, minutesAgo, "HTTP");
    }

    private static void add(TraceStore store, String id, String name, String type, String message, long minutesAgo,
                            String source) {
        CLOCK.at = NOW.minus(Duration.ofMinutes(minutesAgo));
        store.add(record(id, name, type, message, minutesAgo).sourceType(source).build());
    }

    private static final SettableClock CLOCK = new SettableClock();

    private static TraceStore store() {
        return new TraceStore(TraceRecordJsonCodec.INSTANCE, null, 1, 1024 * 1024, 4 * 1024 * 1024,
                task -> false, () -> { }, CLOCK);
    }

    private static final class SettableClock extends java.time.Clock {
        Instant at = NOW;

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }

    private static TraceRecord.Builder record(String id, String name, String type, String message, long minutesAgo) {
        return TraceRecord.builder().traceId(id).sourceType("HTTP").name(name).tag(name).status(500).durationMs(5)
                .error(true).errorType(type).errorMessage(message)
                .finishedAt(NOW.minus(Duration.ofMinutes(minutesAgo)));
    }
}
