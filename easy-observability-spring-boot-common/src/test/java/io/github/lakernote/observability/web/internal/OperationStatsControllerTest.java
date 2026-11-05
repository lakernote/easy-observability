package io.github.lakernote.observability.web.internal;

import io.github.lakernote.observability.stats.internal.OperationStats;
import io.github.lakernote.observability.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationStatsControllerTest {
    private final OperationStats stats = new OperationStats(10);

    @Test
    void returnsTheRequestedWindowAndRejectsUnknownWindows() {
        stats.record(OperationStats.Category.HTTP, "/a · GET", 1_000_000, false);
        var controller = new OperationStatsController(stats, () -> true);

        assertThat(controller.stats("hour").window()).isEqualTo(OperationStats.Window.HOUR);
        assertThat(controller.stats("today").categories().get(0).rows()).hasSize(1);
        assertThatThrownBy(() -> controller.stats("week")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void enforcesViewAndClearPermissions() {
        stats.record(OperationStats.Category.HTTP, "/a · GET", 1_000_000, false);
        var denied = new OperationStatsController(stats, () -> false);
        var viewOnly = new OperationStatsController(stats, () -> true);
        var admin = new OperationStatsController(stats, new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return true;
            }

            @Override
            public boolean canClear() {
                return true;
            }
        });

        assertThatThrownBy(() -> denied.stats("hour")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(viewOnly::reset).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stats.snapshot(OperationStats.Window.TODAY).keys()).isEqualTo(1);

        assertThat(admin.reset().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(stats.snapshot(OperationStats.Window.TODAY).keys()).isZero();
    }

    @Test
    void answersNotFoundWhenStatisticsAreDisabledButStillServesThePage() {
        var controller = new OperationStatsController((OperationStats) null, () -> true);
        assertThatThrownBy(() -> controller.stats("hour")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(controller.page().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void redirectsToTheTrailingSlashPageAndServesHtml() {
        var controller = new OperationStatsController(stats, () -> true);
        assertThat(controller.redirectToStats().getHeaders().getLocation()).hasToString("stats/");
        assertThat(controller.page().getBody()).isNotNull();
        assertThat(controller.page().getBody().exists()).isTrue();
    }
}
