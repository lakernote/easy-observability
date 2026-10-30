package io.github.lakernote.observability.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded endpoints for demonstrating per-entry CPU and allocation alerts. */
@RestController
public class DemoResourceController {
    private static final int ALLOCATION_BLOCK_BYTES = 1024;
    private static final int ALLOCATION_BLOCKS = 24 * 1024;

    @GetMapping("/api/demo/resources/cpu")
    public Map<String, Object> highCpu() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        long work = 0;
        while (System.nanoTime() < deadline) {
            work = Long.rotateLeft(work, 7) ^ System.nanoTime();
        }
        return Map.of("cpuWork", work, "targetRuntimeMs", 200);
    }

    @GetMapping("/api/demo/resources/allocation")
    public Map<String, Object> highAllocation() {
        byte[][] blocks = new byte[ALLOCATION_BLOCKS][];
        long checksum = 0;
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new byte[ALLOCATION_BLOCK_BYTES];
            blocks[i][i % ALLOCATION_BLOCK_BYTES] = (byte) i;
            checksum += blocks[i][i % ALLOCATION_BLOCK_BYTES];
        }
        return Map.of("allocatedBytes", (long) ALLOCATION_BLOCKS * ALLOCATION_BLOCK_BYTES,
                "checksum", checksum);
    }
}
