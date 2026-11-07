package io.github.lakernote.meerkat.starter.boot3;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcConnectionHoldersE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "meerkat.metrics.enabled=false")
class JdbcConnectionHoldersE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final List<Connection> LEAKED = new CopyOnWriteArrayList<>();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) throws java.io.IOException {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("meerkat-connections");
        directory.toFile().deleteOnExit();
        registry.add("meerkat.storage.directory", directory::toString);
    }

    @Test
    void flagsAConnectionThatOutlivesTheRequestThatTookIt() throws Exception {
        try {
            assertThat(get("/query").body()).isEqualTo("1");
            assertThat(JsonPath.<Integer>read(get("/api/meerkat/live/connections").body(), "$.total")).isZero();

            HttpResponse<String> leak = get("/leak");
            assertThat(leak.statusCode()).isEqualTo(200);

            String report = get("/api/meerkat/live/connections").body();
            assertThat(JsonPath.<Integer>read(report, "$.total")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(report, "$.suspectedLeaks")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(report, "$.byDataSource.dataSource")).isEqualTo(1);
            Map<String, Object> held = JsonPath.read(report, "$.items[0]");
            assertThat(held.get("suspectedLeak")).isEqualTo(true);
            assertThat(held.get("threadName")).asString().startsWith("http-nio-");
            assertThat((String) held.get("traceId")).isNotBlank();
        } finally {
            for (Connection connection : LEAKED) connection.close();
            LEAKED.clear();
        }
        assertThat(JsonPath.<Integer>read(get("/api/meerkat/live/connections").body(), "$.total")).isZero();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LeakController.class)
    static class TestApplication {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl("jdbc:h2:mem:connection-holders-e2e;DB_CLOSE_DELAY=-1");
            configuration.setUsername("sa");
            configuration.setPoolName("connection-holders-e2e");
            configuration.setMaximumPoolSize(2);
            return new HikariDataSource(configuration);
        }
    }

    @RestController
    static class LeakController {
        private final DataSource dataSource;

        LeakController(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @GetMapping("/query")
        Integer query() {
            return new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
        }

        @GetMapping("/leak")
        String leak() throws Exception {
            LEAKED.add(dataSource.getConnection());
            return "taken";
        }
    }
}
