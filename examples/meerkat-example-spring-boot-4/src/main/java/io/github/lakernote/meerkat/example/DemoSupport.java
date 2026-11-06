package io.github.lakernote.meerkat.example;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Small helpers shared by the demo endpoints. */
final class DemoSupport {
    private DemoSupport() {
    }

    static void requireMode(String mode) {
        if (!"normal".equals(mode) && !"slow".equals(mode) && !"error".equals(mode)) {
            throw new IllegalArgumentException("mode must be normal, slow or error");
        }
    }

    static int seconds(int requested, int max) {
        return Math.max(1, Math.min(max, requested));
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo pause interrupted", interrupted);
        }
    }

    /** Keeps the current thread busy for {@code millis} and returns a value so the work is not optimized away. */
    static long burnCpu(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        long work = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10_000; i++) work = Long.rotateLeft(work, 7) ^ (i * 0x9E3779B97F4A7C15L);
        }
        return work;
    }

    static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo wait interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Demo task failed", failure.getCause());
        } catch (TimeoutException failure) {
            throw new IllegalStateException("Demo task did not finish in time", failure);
        }
    }

    static String excerpt(String value) {
        return value == null ? "" : value.substring(0, Math.min(240, value.length()));
    }
}
