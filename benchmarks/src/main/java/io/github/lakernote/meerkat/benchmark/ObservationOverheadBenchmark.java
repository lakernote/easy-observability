package io.github.lakernote.meerkat.benchmark;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.observation.internal.ObservationTraceHandler;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cost the starter adds to one fast entry request observed through Micrometer Observation: an
 * {@code http.server.requests} root with {@code dependencies} child calls (JDBC, Redis, ...), below the slow
 * threshold so nothing is retained. The baseline runs the same observations through a no-op handler, so the
 * difference is the starter's own overhead.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ObservationOverheadBenchmark {
    @Param({"0", "5", "20"})
    public int dependencies;

    /** Call statistics are on by default; {@code false} isolates the Trace cost. */
    @Param({"true", "false"})
    public boolean stats;

    private ObservationRegistry baseline;
    private ObservationRegistry instrumented;

    @Setup
    public void setUp() {
        baseline = ObservationRegistry.create();
        baseline.observationConfig().observationHandler(new NoopHandler());

        instrumented = ObservationRegistry.create();
        instrumented.observationConfig().observationHandler(new ObservationTraceHandler(
                TraceStore.inMemory(32L * 1024 * 1024), new MeerkatProperties(), List.of(),
                stats ? new OperationStats(1_000) : null));
    }

    @TearDown(Level.Iteration)
    public void clearThreadState() {
        TraceContext.clear();
        TraceIds.clear();
    }

    @Benchmark
    public void baselineRequest() {
        request(baseline);
    }

    @Benchmark
    public void instrumentedRequest() {
        request(instrumented);
    }

    private void request(ObservationRegistry registry) {
        Observation root = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("method", "GET")
                .lowCardinalityKeyValue("uri", "/orders/{id}")
                .lowCardinalityKeyValue("status", "200")
                .start();
        try (Observation.Scope ignored = root.openScope()) {
            for (int i = 0; i < dependencies; i++) {
                Observation.createNotStarted("spring.data.redis", registry)
                        .contextualName("GET")
                        .lowCardinalityKeyValue("db.operation", "GET")
                        .parentObservation(root)
                        .start()
                        .stop();
            }
        } finally {
            root.stop();
        }
    }

    static final class NoopHandler implements ObservationHandler<Observation.Context> {
        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
