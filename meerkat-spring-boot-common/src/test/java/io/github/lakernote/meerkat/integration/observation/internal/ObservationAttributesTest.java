package io.github.lakernote.meerkat.integration.observation.internal;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationAttributesTest {

    @Test
    void httpDisplayNameCombinesRouteAndMethod() {
        Observation.Context context = context("http.server.requests");
        context.addLowCardinalityKeyValue(KeyValue.of("uri", "/orders/{id}"));
        context.addLowCardinalityKeyValue(KeyValue.of("method", "GET"));

        String operation = ObservationAttributes.operationName(context, ObservationType.HTTP);
        String detail = ObservationAttributes.detail(context, ObservationType.HTTP);

        assertThat(detail).isEqualTo("/orders/{id}");
        assertThat(ObservationAttributes.displayName(context, ObservationType.HTTP, operation, detail))
                .isEqualTo("/orders/{id} · GET");
    }

    @Test
    void kafkaDetailListsKnownCoordinatesAndSkipsNone() {
        Observation.Context context = context("spring.kafka.listener");
        context.addLowCardinalityKeyValue(KeyValue.of("messaging.source.name", "orders"));
        context.addHighCardinalityKeyValue(KeyValue.of("messaging.kafka.source.partition", "3"));
        context.addHighCardinalityKeyValue(KeyValue.of("messaging.kafka.message.offset", "42"));
        context.addLowCardinalityKeyValue(KeyValue.of("messaging.kafka.consumer.group", "none"));
        context.addLowCardinalityKeyValue(KeyValue.of("spring.kafka.listener.id", "orders-1"));

        assertThat(ObservationAttributes.detail(context, ObservationType.KAFKA_CONSUMER))
                .isEqualTo("topic=orders, partition=3, offset=42, listener=orders-1");
        assertThat(ObservationAttributes.operationName(context, ObservationType.KAFKA_CONSUMER))
                .isEqualTo("orders-1");
    }

    @Test
    void operationNameFallsBackToContextualNameThenObservationNameThenType() {
        Observation.Context context = context("spring.transaction");
        assertThat(ObservationAttributes.operationName(context, ObservationType.TRANSACTION))
                .isEqualTo("spring.transaction");
        context.setContextualName("OrderService#create");
        assertThat(ObservationAttributes.operationName(context, ObservationType.TRANSACTION))
                .isEqualTo("OrderService#create");
        assertThat(ObservationAttributes.operationName(new Observation.Context(), ObservationType.TRANSACTION))
                .isEqualTo("TRANSACTION");
    }

    @Test
    void valuesAreSingleLineAndBounded() {
        assertThat(ObservationAttributes.safe("a\r\nb", 10)).isEqualTo("a  b");
        assertThat(ObservationAttributes.safe("abcdef", 3)).isEqualTo("abc");
        assertThat(ObservationAttributes.safe(null, 3)).isEmpty();
    }

    @Test
    void dashboardPathsIgnoreQueryAndLookalikes() {
        assertThat(ObservationAttributes.isDashboardPath("/meerkat")).isTrue();
        assertThat(ObservationAttributes.isDashboardPath("/meerkat/assets/app.js")).isTrue();
        assertThat(ObservationAttributes.isDashboardPath("/api/meerkat/traces?limit=5")).isTrue();
        assertThat(ObservationAttributes.isDashboardPath("/meerkat-admin")).isFalse();
        assertThat(ObservationAttributes.isDashboardPath("/api/meerkatx")).isFalse();
        assertThat(ObservationAttributes.isDashboardPath("")).isFalse();
    }

    private static Observation.Context context(String name) {
        Observation.Context context = new Observation.Context();
        context.setName(name);
        return context;
    }
}
