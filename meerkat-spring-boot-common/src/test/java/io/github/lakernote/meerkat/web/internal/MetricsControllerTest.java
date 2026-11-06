package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.metrics.internal.MetricsArchive;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.metrics.internal.ResourceHistory;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsControllerTest {
    private final MetricsArchive archive = new MetricsArchive(null, 1024, 30, ZoneOffset.UTC);
    private final OperationStats stats = new OperationStats(10);
    private final ResourceHistory resources = new ResourceHistory(null);

    @Test
    void returnsRankingsAndTrendsAndRejectsUnknownParameters() {
        stats.record(OperationStats.Category.HTTP, "/a · GET", 1_000_000, false);
        resources.record(System.currentTimeMillis(), Map.of("jvm.threads.live", 12.0));
        var controller = controller(() -> true);

        assertThat(controller.operations("1h").window()).isEqualTo(OperationStats.Window.HOUR);
        assertThat(controller.operations("today").categories().get(0).rows()).hasSize(1);
        assertThat(controller.operationTrend("http", "/a · GET", "1h").points().stream()
                .mapToLong(OperationStats.Point::count).sum()).isEqualTo(1);
        assertThat(controller.operationTrends("24h")).containsKey(OperationStats.Category.SQL);
        assertThat(controller.resources("1h").series()).containsKey("jvm.threads.live");
        assertThat(controller.storage().persistent()).isFalse();
        for (Runnable invalid : new Runnable[] {
                () -> controller.operations("week"),
                () -> controller.operationTrend("nope", "x", "1h"),
                () -> controller.operationTrend("HTTP", "x", "today"),
                () -> controller.operationTrends("1y"),
                () -> controller.resources("2h")}) {
            assertThatThrownBy(invalid::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void enforcesViewAndClearPermissions() {
        stats.record(OperationStats.Category.HTTP, "/a · GET", 1_000_000, false);
        var denied = controller(() -> false);
        var viewOnly = controller(() -> true);
        var admin = controller(new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return true;
            }

            @Override
            public boolean canClear() {
                return true;
            }
        });

        for (Runnable call : new Runnable[] {() -> denied.operations("1h"), () -> denied.resources("1h"),
                () -> denied.operationTrends("1h"), denied::storage, viewOnly::reset}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(stats.snapshot(OperationStats.Window.TODAY).keys()).isEqualTo(1);

        assertThat(admin.reset().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(stats.snapshot(OperationStats.Window.TODAY).keys()).isZero();
    }

    @Test
    void answersNotFoundWhenMetricsAreDisabledButStillServesThePages() {
        var controller = new MetricsController((OperationStats) null, null, null, () -> true);
        for (Runnable call : new Runnable[] {() -> controller.operations("1h"), () -> controller.resources("1h"),
                controller::storage}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        assertThat(controller.statsPage().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.metricsPage().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void redirectsToTheTrailingSlashPagesAndServesHtml() {
        var controller = controller(() -> true);
        assertThat(controller.redirectToStats().getHeaders().getLocation()).hasToString("stats/");
        assertThat(controller.redirectToMetrics().getHeaders().getLocation()).hasToString("metrics/");
        assertThat(controller.statsPage().getBody()).isNotNull();
        assertThat(controller.statsPage().getBody().exists()).isTrue();
        assertThat(controller.metricsPage().getBody().exists()).isTrue();
    }

    private MetricsController controller(ObservabilityAccessPolicy policy) {
        return new MetricsController(stats, resources, archive, policy);
    }
}
