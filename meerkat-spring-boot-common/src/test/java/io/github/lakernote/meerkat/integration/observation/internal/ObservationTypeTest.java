package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTypeTest {

    @Test
    void exactNamesDoNotMatchLongerNames() {
        assertThat(ObservationType.of("http.server.requests")).isEqualTo(ObservationType.HTTP);
        assertThat(ObservationType.of("HTTP.SERVER.REQUESTS")).isEqualTo(ObservationType.HTTP);
        assertThat(ObservationType.of("http.server.requests.active")).isNull();
        assertThat(ObservationType.of("http.client.requests")).isEqualTo(ObservationType.REMOTE);
        assertThat(ObservationType.of("tasks.scheduled.execution")).isEqualTo(ObservationType.JOB);
    }

    @Test
    void prefixNamesMatchOnlyAtSegmentBoundaries() {
        assertThat(ObservationType.of("spring.data.redis")).isEqualTo(ObservationType.REDIS);
        assertThat(ObservationType.of("lettuce.command")).isEqualTo(ObservationType.REDIS);
        assertThat(ObservationType.of("lettucex")).isNull();
        assertThat(ObservationType.of("jdbc.query")).isEqualTo(ObservationType.DATABASE);
        assertThat(ObservationType.of("spring.kafka.listener")).isEqualTo(ObservationType.KAFKA_CONSUMER);
        assertThat(ObservationType.of("spring.kafka.template")).isEqualTo(ObservationType.KAFKA_PRODUCER);
        assertThat(ObservationType.of("spring.transaction")).isEqualTo(ObservationType.TRANSACTION);
        assertThat(ObservationType.of(null)).isNull();
        assertThat(ObservationType.of("unrelated")).isNull();
    }

    @Test
    void onlyEntryPointsHaveSlowThresholds() {
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTrace().setHttpSlowThreshold(Duration.ofMillis(11));
        properties.getTrace().setJobSlowThreshold(Duration.ofMillis(22));
        properties.getTrace().setKafkaConsumerSlowThreshold(Duration.ofMillis(33));

        for (ObservationType type : ObservationType.values()) {
            long threshold = type.slowThresholdMs(properties);
            assertThat(threshold > 0).as(type.name()).isEqualTo(type.entryPoint);
        }
        assertThat(ObservationType.HTTP.slowThresholdMs(properties)).isEqualTo(11);
        assertThat(ObservationType.JOB.slowThresholdMs(properties)).isEqualTo(22);
        assertThat(ObservationType.KAFKA_CONSUMER.slowThresholdMs(properties)).isEqualTo(33);
    }
}
