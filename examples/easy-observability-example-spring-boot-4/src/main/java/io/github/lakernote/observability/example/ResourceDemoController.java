package io.github.lakernote.observability.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Entry-thread CPU time and allocation above the demo thresholds (100 ms, 8 MB), without any dependency. */
@RestController
@RequestMapping("/api/demo/resource")
public class ResourceDemoController {
    private static final int BLOCK_BYTES = 1024;
    private static final int BLOCKS = 24 * 1024;

    /** About 200 ms of computation on the request thread. */
    @GetMapping("/cpu")
    public Map<String, Object> cpu() {
        return Map.of("cpuWork", DemoSupport.burnCpu(200), "targetRuntimeMs", 200);
    }

    /** Allocates about 24 MiB of short-lived arrays; they become garbage right after the response. */
    @GetMapping("/allocation")
    public Map<String, Object> allocation() {
        byte[][] blocks = new byte[BLOCKS][];
        long checksum = 0;
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new byte[BLOCK_BYTES];
            blocks[i][i % BLOCK_BYTES] = (byte) i;
            checksum += blocks[i][i % BLOCK_BYTES];
        }
        return Map.of("allocatedBytes", (long) BLOCKS * BLOCK_BYTES, "checksum", checksum);
    }
}
