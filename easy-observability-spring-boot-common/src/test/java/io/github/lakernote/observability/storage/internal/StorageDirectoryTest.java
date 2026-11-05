package io.github.lakernote.observability.storage.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StorageDirectoryTest {

    @TempDir
    Path temp;

    @Test
    void ownsTheDirectoryAndExposesTheStoreLocations() {
        Path root = temp.resolve("app");
        try (StorageDirectory storage = StorageDirectory.acquire(root)) {
            assertThat(storage.available()).isTrue();
            assertThat(root.resolve(StorageDirectory.LOCK_FILE)).isRegularFile();
            assertThat(storage.traceFile()).isEqualTo(root.resolve("traces.jsonl"));
            assertThat(storage.metricsDirectory()).isEqualTo(root.resolve("metrics"));
        }
    }

    @Test
    void secondOwnerInTheSameProcessFallsBackToMemoryUntilTheFirstCloses() {
        Path root = temp.resolve("shared");
        StorageDirectory first = StorageDirectory.acquire(root);
        try (StorageDirectory second = StorageDirectory.acquire(root)) {
            assertThat(first.available()).isTrue();
            assertThat(second.available()).isFalse();
            assertThat(second.traceFile()).isNull();
            assertThat(second.metricsDirectory()).isNull();
        } finally {
            first.close();
        }

        try (StorageDirectory third = StorageDirectory.acquire(root)) {
            assertThat(third.available()).isTrue();
        }
    }

    @Test
    void directoryOwnedByAnotherProcessIsNotShared() throws Exception {
        Path root = temp.resolve("other-process");
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(), root.toString())
                .redirectErrorStream(true)
                .start();
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(readUntilStatus(output)).isEqualTo(LockHolder.LOCKED);

            try (StorageDirectory contender = StorageDirectory.acquire(root)) {
                assertThat(contender.available()).isFalse();
            }
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(10, TimeUnit.SECONDS)) holder.destroyForcibly();
        }

        try (StorageDirectory afterExit = StorageDirectory.acquire(root)) {
            assertThat(afterExit.available()).isTrue();
        }
    }

    @Test
    void unusableDirectoryFallsBackToMemoryWithoutThrowing() throws IOException {
        Path blockingFile = Files.writeString(temp.resolve("not-a-directory"), "x");

        try (StorageDirectory storage = StorageDirectory.acquire(blockingFile.resolve("app"))) {
            assertThat(storage.available()).isFalse();
            assertThat(storage.traceFile()).isNull();
        }
    }

    private static String readUntilStatus(BufferedReader output) throws IOException {
        String line;
        while ((line = output.readLine()) != null) {
            if (line.equals(LockHolder.LOCKED) || line.equals(LockHolder.UNAVAILABLE)) return line;
        }
        return null;
    }

    /** Holds the storage directory in a separate JVM until its standard input is closed. */
    public static final class LockHolder {
        static final String LOCKED = "LOCKED";
        static final String UNAVAILABLE = "UNAVAILABLE";

        public static void main(String[] args) throws IOException {
            try (StorageDirectory storage = StorageDirectory.acquire(Path.of(args[0]))) {
                System.out.println(storage.available() ? LOCKED : UNAVAILABLE);
                System.out.flush();
                while (System.in.read() != -1) {
                    // Wait for the parent test to release the holder.
                }
            }
        }
    }
}
