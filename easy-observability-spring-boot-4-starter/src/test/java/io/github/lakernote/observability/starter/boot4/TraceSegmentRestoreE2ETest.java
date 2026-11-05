package io.github.lakernote.observability.starter.boot4;

import io.github.lakernote.observability.trace.internal.TraceNode;
import io.github.lakernote.observability.trace.internal.store.TraceRecord;
import io.github.lakernote.observability.trace.internal.store.TraceStore;
import io.github.lakernote.observability.trace.internal.store.TraceStoreTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Traces written by an earlier run are listed, searched and opened after a restart; expired files are removed. */
@SpringBootTest(classes = TraceSegmentRestoreE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.trace.enabled=false",
                "easy.observability.trace.retention=7d"
        })
class TraceSegmentRestoreE2ETest {
    private static final int RECORD_COUNT = 140;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.systemDefault());
    private static final Path STORAGE = createHistory();
    private static final Path EXPIRED = STORAGE.resolve("traces").resolve(segmentName(TODAY.minusDays(30)));
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("easy.observability.storage.directory", STORAGE::toString);
    }

    @AfterAll
    static void cleanStoragePath() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE);
    }

    @Test
    void restoresTheIndexAndServesPagesSearchesAndDetailsFromDisk() throws Exception {
        awaitIndexed();
        assertThat(EXPIRED).doesNotExist();

        String storage = get("/api/easy-observability/traces/storage").body();
        assertThat(JsonPath.<Boolean>read(storage, "$.persistent")).isTrue();
        assertThat(JsonPath.<Integer>read(storage, "$.traceCount")).isEqualTo(RECORD_COUNT);
        assertThat(JsonPath.<Integer>read(storage, "$.retentionDays")).isEqualTo(7);

        List<String> seen = new ArrayList<>();
        String cursor = "";
        int pages = 0;
        while (cursor != null && pages < 10) {
            String body = get("/api/easy-observability/traces?limit=50&before=" + cursor).body();
            assertThat(body).doesNotContain("treeNodes");
            seen.addAll(JsonPath.read(body, "$.items[*].traceId"));
            assertThat(JsonPath.<Integer>read(body, "$.total")).isEqualTo(RECORD_COUNT);
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(RECORD_COUNT).doesNotHaveDuplicates();
        assertThat(seen.get(0)).isEqualTo("restore-" + (RECORD_COUNT - 1));
        assertThat(seen.get(RECORD_COUNT - 1)).isEqualTo("restore-0");

        String search = get("/api/easy-observability/traces?q="
                + URLEncoder.encode("FROM ledger_77 ", StandardCharsets.UTF_8)).body();
        assertThat(JsonPath.<List<String>>read(search, "$.items[*].traceId")).containsExactly("restore-77");
        assertThat(JsonPath.<Integer>read(search, "$.total")).isEqualTo(-1);

        String errors = get("/api/easy-observability/traces?kind=error&limit=100").body();
        assertThat(JsonPath.<Integer>read(errors, "$.total")).isEqualTo(RECORD_COUNT / 2);
        assertThat(JsonPath.<List<String>>read(errors, "$.items[*].traceId")).hasSize(RECORD_COUNT / 2);

        HttpResponse<String> detail = get("/api/easy-observability/traces/restore-77?source=HTTP");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(detail.body(), "$.treeNodes[0].tag"))
                .isEqualTo("select * FROM ledger_77 where id = ?");
        assertThat(get("/api/easy-observability/traces/missing").statusCode()).isEqualTo(404);
        assertThat(get("/api/easy-observability/traces?kind=bogus").statusCode()).isEqualTo(400);
    }

    private void awaitIndexed() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (traceStore.storageStatus().indexing() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(traceStore.storageStatus().indexing()).isFalse();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String segmentName(LocalDate day) {
        return "traces-" + DateTimeFormatter.BASIC_ISO_DATE.format(day) + "-1.jsonl";
    }

    private static Path createHistory() {
        try {
            Path directory = Files.createTempDirectory("easy-observability-segment-restore");
            Path traces = Files.createDirectories(directory.resolve("traces"));
            Files.writeString(traces.resolve(segmentName(TODAY.minusDays(30))), "{}\n");
            var startedAt = TODAY.minusDays(1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant();
            try (BufferedWriter writer = Files.newBufferedWriter(traces.resolve(segmentName(TODAY.minusDays(1))),
                    StandardCharsets.UTF_8)) {
                for (int index = 0; index < RECORD_COUNT; index++) {
                    TraceNode sql = new TraceNode("DB", "SQL", "select * FROM ledger_" + index + " where id = ?",
                            5, 1, false, false, false, "", "main", 1, List.of());
                    writer.write(TraceStoreTestSupport.json(TraceRecord.builder()
                            .id(index + 1L).traceId("restore-" + index).sourceType("HTTP")
                            .name("GET /restore").tag("GET").status(index % 2 == 0 ? 500 : 200).durationMs(900)
                            .slow(index % 2 == 1).error(index % 2 == 0).finishedAt(startedAt.plusSeconds(index))
                            .treeNodes(List.of(sql)).build()));
                    writer.newLine();
                }
            }
            return directory;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication { }
}
