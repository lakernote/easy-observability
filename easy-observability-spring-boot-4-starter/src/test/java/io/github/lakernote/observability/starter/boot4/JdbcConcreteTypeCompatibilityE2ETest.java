package io.github.lakernote.observability.starter.boot4;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcConcreteTypeCompatibilityE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.web.enabled=false"
        })
class JdbcConcreteTypeCompatibilityE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private HikariDataSource dataSource;

    @Test
    void preservesConcreteDataSourceInjectionAndTracesRealSql() throws Exception {
        assertThat(dataSource).isInstanceOf(ProxyJdbcObject.class);
        assertThat(dataSource.getPoolName()).isEqualTo("jdbc-compatibility-e2e");

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/jdbc-probe"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("42");

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection).isInstanceOf(ProxyJdbcObject.class);
        }

        AtomicReference<TraceContext.DeferredCompletion> trace = new AtomicReference<>();
        TraceContext.startRoot("jdbc-e2e", SpanKind.HTTP);
        try {
            assertThat(new JdbcTemplate(dataSource)
                    .queryForObject("SELECT answer FROM jdbc_probe WHERE id = 1", Integer.class)).isEqualTo(42);
        } finally {
            TraceContext.stopRootDeferred(null, trace::set);
        }
        assertThat(trace.get()).isNotNull();
        assertThat(trace.get().tree()).contains("SQL SELECT");
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(JdbcProbeController.class)
    static class TestApplication {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl("jdbc:h2:mem:jdbc-compatibility-e2e;DB_CLOSE_DELAY=-1");
            configuration.setUsername("sa");
            configuration.setPoolName("jdbc-compatibility-e2e");
            configuration.setMaximumPoolSize(2);
            return new HikariDataSource(configuration);
        }
    }

    @RestController
    static class JdbcProbeController {
        private final JdbcTemplate jdbcTemplate;

        JdbcProbeController(HikariDataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @GetMapping("/jdbc-probe")
        int probe() {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS jdbc_probe (id INT PRIMARY KEY, answer INT)");
            jdbcTemplate.update("MERGE INTO jdbc_probe KEY(id) VALUES (1, 42)");
            return jdbcTemplate.queryForObject("SELECT answer FROM jdbc_probe WHERE id = 1", Integer.class);
        }
    }
}
