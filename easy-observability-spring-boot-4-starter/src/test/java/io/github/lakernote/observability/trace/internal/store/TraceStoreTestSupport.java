package io.github.lakernote.observability.trace.internal.store;

import io.github.lakernote.observability.trace.internal.BoundedHistoryWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

/** Test access to the package-private trace file codec and store hooks. */
public final class TraceStoreTestSupport {
    private TraceStoreTestSupport() {
    }

    /** Encodes one segment line exactly as {@link TraceStore} writes it, without the line terminator. */
    public static String json(TraceRecord trace) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            TraceRecordJsonCodec.INSTANCE.write(output, trace);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Creates and starts a store that invokes {@code hook} on its writer thread before each record is encoded. */
    public static TraceStore storeWithWriteHook(Path directory, WriteHook hook) {
        TraceRecordJsonCodec codec = new TraceRecordJsonCodec() {
            @Override
            void write(OutputStream output, TraceRecord trace) throws IOException {
                hook.beforeWrite(trace);
                super.write(output, trace);
            }
        };
        BoundedHistoryWriter writer = new BoundedHistoryWriter();
        TraceStore store = new TraceStore(codec, directory, 7, 128L * 1024 * 1024, 32L * 1024 * 1024, writer::execute,
                writer::close, Clock.systemDefaultZone());
        store.start();
        return store;
    }

    @FunctionalInterface
    public interface WriteHook {
        void beforeWrite(TraceRecord trace) throws IOException;
    }
}
