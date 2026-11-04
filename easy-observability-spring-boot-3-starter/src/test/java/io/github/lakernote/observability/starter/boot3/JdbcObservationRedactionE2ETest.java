package io.github.lakernote.observability.starter.boot3;

import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceContext;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcObservationRedactionE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "easy.observability.metrics.enabled=false",
                "easy.observability.web.enabled=false"
        })
class JdbcObservationRedactionE2ETest {
    @Autowired
    private ObservationRegistry observationRegistry;

    @AfterEach
    void clearContexts() {
        TraceContext.clear();
        TraceIdContext.clear();
    }

    @Test
    void starterRedactsJdbcObservationSqlAndDriverErrors() {
        String sqlSecret = "boot3-observation-secret--tail";
        String causeSecret = "boot3-bind-secret";
        SQLException failure = new SQLException(
                "Syntax error in SQL SELECT '" + sqlSecret + "'",
                new IllegalArgumentException(causeSecret));
        AtomicReference<TraceContext.DeferredCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-observation-e2e", SpanKind.HTTP);
        Observation observation = Observation.createNotStarted("spring.jdbc", observationRegistry)
                .contextualName("SELECT")
                .lowCardinalityKeyValue("db.operation", "SELECT")
                .lowCardinalityKeyValue("jdbc.datasource.name", "orders")
                .highCardinalityKeyValue("jdbc.query[0]",
                        "SELECT '" + sqlSecret + "' FROM missing_table");
        observation.start();
        observation.error(failure);
        observation.stop();
        TraceContext.stopRootDeferred(null, completion::set);

        TraceContext.DeferredCompletion result = completion.get();
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly("DATABASE_ERROR");
        assertThat(result.errorType()).isEqualTo(SQLException.class.getName());
        assertThat(result.errorMessage()).isEqualTo("JDBC query failed");
        assertThat(result.stackTrace())
                .contains(SQLException.class.getName(), IllegalArgumentException.class.getName())
                .doesNotContain(sqlSecret, causeSecret, "Syntax error in SQL", "secret");
        assertThat(result.tree())
                .contains("[Database] SELECT", "orders · SELECT ? FROM missing_table")
                .doesNotContain(sqlSecret, causeSecret, "secret");
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
