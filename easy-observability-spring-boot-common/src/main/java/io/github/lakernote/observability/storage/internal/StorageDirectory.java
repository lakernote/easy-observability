package io.github.lakernote.observability.storage.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The local directory that holds the trace archive and metric history, owned exclusively by one process.
 *
 * <p>Two instances writing the same files would corrupt them, so the directory is guarded by an OS file lock held
 * for the application's lifetime. When the lock cannot be acquired, or the directory cannot be created, the
 * directory is reported as unavailable and the stores keep their history in memory only; the application itself
 * always starts.</p>
 */
public final class StorageDirectory implements AutoCloseable {
    static final String LOCK_FILE = ".lock";
    private static final Logger log = LoggerFactory.getLogger(StorageDirectory.class);

    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private StorageDirectory(Path root, FileChannel lockChannel, FileLock lock) {
        this.root = root;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    /** Creates the directory if needed and tries to take exclusive ownership of it. Never throws. */
    public static StorageDirectory acquire(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        FileChannel channel = null;
        try {
            Files.createDirectories(root);
            channel = FileChannel.open(root.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = tryLock(channel);
            if (lock != null) return new StorageDirectory(root, channel, lock);
            log.warn("Easy Observability storage directory {} is already used by another application instance; "
                    + "trace and metric history are kept in memory only. Give each instance its own "
                    + "easy.observability.storage.directory.", root);
        } catch (IOException | RuntimeException failure) {
            log.warn("Easy Observability storage directory {} is not usable; trace and metric history are kept "
                    + "in memory only: {}", root, failure.toString());
        }
        closeQuietly(channel);
        return new StorageDirectory(root, null, null);
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException heldByThisProcess) {
            // Another application context in the same JVM already owns the directory.
            return null;
        }
    }

    /** Whether this process owns the directory and may persist history in it. */
    public boolean available() {
        return lock != null && lock.isValid();
    }

    public Path root() {
        return root;
    }

    /** The trace segment directory, or {@code null} when the directory is unavailable. */
    public Path traceDirectory() {
        return available() ? root.resolve("traces") : null;
    }

    /** The metric history directory, or {@code null} when the directory is unavailable. */
    public Path metricsDirectory() {
        return available() ? root.resolve("metrics") : null;
    }

    @Override
    public void close() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below releases the lock as well.
            }
        }
        closeQuietly(lockChannel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing else holds the channel; the OS releases it when the process exits.
        }
    }
}
