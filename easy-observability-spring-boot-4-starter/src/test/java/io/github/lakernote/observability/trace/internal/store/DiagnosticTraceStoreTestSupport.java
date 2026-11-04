package io.github.lakernote.observability.trace.internal.store;

import io.github.lakernote.observability.trace.internal.BoundedHistoryWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Test access to the package-private archive codec. */
public final class DiagnosticTraceStoreTestSupport {
    private DiagnosticTraceStoreTestSupport() {
    }

    /** Encodes one archive line exactly as {@link DiagnosticTraceStore} writes it, without the line terminator. */
    public static String json(RecentTraceStore.TraceRecord trace) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            TraceRecordJsonCodec.INSTANCE.write(output, trace);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Creates a store that invokes {@code hook} on the archive writer thread before each record is encoded. */
    public static DiagnosticTraceStore storeWithWriteHook(String historyFile, int maxHistorySize,
                                                          long maxHistoryFileBytes, BoundedHistoryWriter writer,
                                                          WriteHook hook) {
        TraceRecordJsonCodec codec = new TraceRecordJsonCodec() {
            @Override
            void write(OutputStream output, RecentTraceStore.TraceRecord trace) throws IOException {
                hook.beforeWrite(trace);
                super.write(output, trace);
            }
        };
        return new DiagnosticTraceStore(codec, historyFile, maxHistorySize, maxHistoryFileBytes, writer);
    }

    @FunctionalInterface
    public interface WriteHook {
        void beforeWrite(RecentTraceStore.TraceRecord trace) throws IOException;
    }
}
