package io.github.lakernote.observability.starter.boot4;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = OperationStatsE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.storage.directory=target/operation-stats-e2e"
        })
class OperationStatsE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Test
    void ranksRealHttpEndpointsAndSqlTemplatesOnTheStatisticsPage() throws Exception {
        assertThat(get("/orders/1?items=1").statusCode()).isEqualTo(200);
        assertThat(get("/orders/2?items=3").statusCode()).isEqualTo(200);
        assertThat(get("/orders/3?items=5&sleepMs=600").statusCode()).isEqualTo(200);
        assertThat(get("/fail").statusCode()).isEqualTo(500);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            String body = get("/api/easy-observability/stats?window=hour").body();
            assertThat(body)
                    .contains("\"window\":\"HOUR\"", "\"category\":\"HTTP\"", "\"category\":\"SQL\"")
                    .contains("\"name\":\"/orders/{id} · GET\",\"traceQuery\":\"/orders/{id} · GET\",\"count\":3,"
                            + "\"errors\":0")
                    .contains("\"name\":\"/fail · GET\",\"traceQuery\":\"/fail · GET\",\"count\":1,\"errors\":1")
                    .contains("\"name\":\"SELECT COUNT(*) FROM orders WHERE id IN (?…)\","
                            + "\"traceQuery\":\"SELECT COUNT(*) FROM orders WHERE id IN\",\"count\":3,\"errors\":0")
                    .doesNotContain("easy-observability/");
        });
        // The Trace Explorer filters by substring, so the collapsed SQL row's traceQuery must occur in the slow trace.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                get("/api/easy-observability/traces?limit=500").body())
                .contains("\"slow\":true", "SELECT COUNT(*) FROM orders WHERE id IN (?, ?, ?, ?, ?)"));
        assertThat(get("/api/easy-observability/stats?window=today").body())
                .contains("\"window\":\"TODAY\"", "\"persistent\":true", "\"name\":\"/fail · GET\"");
        assertThat(get("/api/easy-observability/stats?window=30d").body())
                .contains("\"window\":\"MONTH\"", "\"name\":\"/fail · GET\"");
        assertThat(get("/api/easy-observability/stats?window=week").statusCode()).isEqualTo(400);
        assertThat(HTTP_CLIENT.send(HttpRequest.newBuilder(uri("/api/easy-observability/stats/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);

        HttpResponse<String> redirect = get("/easy-observability/stats");
        assertThat(redirect.statusCode()).isEqualTo(308);
        assertThat(redirect.headers().firstValue("Location")).hasValue("stats/");
        HttpResponse<String> page = get("/easy-observability/stats/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("调用排行", "../assets/stats.js", "../metrics/");
        assertThat(get("/easy-observability/assets/stats.js").statusCode()).isEqualTo(200);
        assertThat(get("/easy-observability/assets/stats.css").statusCode()).isEqualTo(200);
        assertThat(get("/easy-observability/").body()).contains("href=\"stats/\"");
    }

    @Test
    void keepsTheDayRankingAcrossRestarts() throws Exception {
        Path directory = Files.createTempDirectory("operation-stats-restart");
        try {
            for (int run = 1; run <= 2; run++) {
                try (ConfigurableApplicationContext application = new SpringApplicationBuilder(TestApplication.class)
                        .properties("server.port=0", "easy.observability.metrics.enabled=false",
                                "easy.observability.storage.directory=" + directory)
                        .run()) {
                    int applicationPort = Integer.parseInt(
                            application.getEnvironment().getRequiredProperty("local.server.port"));
                    assertThat(get(applicationPort, "/orders/1?items=1").statusCode()).isEqualTo(200);
                    long expected = run;
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            get(applicationPort, "/api/easy-observability/stats?window=today").body())
                            .contains("\"persistent\":true", "\"name\":\"/orders/{id} · GET\","
                                    + "\"traceQuery\":\"/orders/{id} · GET\",\"count\":" + expected + ","));
                }
            }
            try (var files = Files.list(directory.resolve("stats"))) {
                assertThat(files.map(file -> file.getFileName().toString()))
                        .singleElement().asString().matches("\\d{8}\\.tsv");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private HttpResponse<String> get(int applicationPort, String path) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(OrdersController.class)
    static class TestApplication {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl("jdbc:h2:mem:operation-stats-e2e;DB_CLOSE_DELAY=-1");
            configuration.setUsername("sa");
            configuration.setMaximumPoolSize(2);
            return new HikariDataSource(configuration);
        }
    }

    @RestController
    static class OrdersController {
        private final JdbcTemplate jdbcTemplate;

        OrdersController(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY)");
        }

        @GetMapping("/orders/{id}")
        Integer order(@PathVariable int id, @RequestParam int items, @RequestParam(defaultValue = "0") long sleepMs)
                throws InterruptedException {
            Thread.sleep(sleepMs);
            String placeholders = String.join(", ", Collections.nCopies(items, "?"));
            Object[] ids = new Object[items];
            for (int i = 0; i < items; i++) ids[i] = id + i;
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE id IN (" + placeholders + ")",
                    Integer.class, ids);
        }

        @GetMapping("/fail")
        String fail() {
            throw new IllegalStateException("boom");
        }
    }
}
