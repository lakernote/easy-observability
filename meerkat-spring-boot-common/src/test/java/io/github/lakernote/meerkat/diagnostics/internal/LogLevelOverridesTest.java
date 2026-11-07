package io.github.lakernote.meerkat.diagnostics.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class LogLevelOverridesTest {
    private final FakeLoggingSystem logging = new FakeLoggingSystem();
    private final LogLevelOverrides overrides =
            new LogLevelOverrides(logging, Duration.ofMinutes(10), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @AfterEach
    void close() {
        overrides.close();
    }

    @Test
    void listsSupportedLevelsAndFiltersLoggers() {
        logging.levels.put(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
        logging.levels.put("com.example.Order", LogLevel.WARN);
        logging.levels.put("com.example.Payment", null);
        logging.levels.put("org.other", null);

        assertThat(overrides.levels()).containsExactly("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF");
        assertThat(overrides.maxDurationSeconds()).isEqualTo(600);
        LogLevelOverrides.LoggerList all = overrides.loggers(null, 1000);
        assertThat(all.total()).isEqualTo(4);
        assertThat(all.loggers()).hasSize(4);
        LogLevelOverrides.LoggerList example = overrides.loggers(" EXAMPLE ", 1);
        assertThat(example.total()).isEqualTo(2);
        assertThat(example.loggers()).singleElement().satisfies(logger -> {
            assertThat(logger.name()).isEqualTo("com.example.Order");
            assertThat(logger.configuredLevel()).isEqualTo("WARN");
            assertThat(logger.effectiveLevel()).isEqualTo("WARN");
            assertThat(logger.overriddenUntil()).isNull();
        });
        assertThat(overrides.loggers("example.payment", 10).loggers().get(0).configuredLevel()).isNull();
    }

    @Test
    void setsATemporaryLevelAndRevertsToThePreviousConfiguredLevel() {
        logging.levels.put("com.example", LogLevel.WARN);

        LogLevelOverrides.OverrideView view =
                overrides.set("com.example", "debug", Duration.ofMinutes(5), "alice@127.0.0.1\r\n");

        assertThat(view.level()).isEqualTo("DEBUG");
        assertThat(view.previousLevel()).isEqualTo("WARN");
        assertThat(view.operator()).isEqualTo("alice@127.0.0.1");
        assertThat(view.expiresAt()).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(5)));
        assertThat(view.remainingSeconds()).isEqualTo(300);
        assertThat(logging.levels).containsEntry("com.example", LogLevel.DEBUG);
        assertThat(overrides.loggers("com.example", 10).loggers().get(0).overriddenUntil()).isEqualTo(view.expiresAt());

        // Changing it again keeps the level from before the first override.
        assertThat(overrides.set("com.example", "TRACE", Duration.ofMinutes(1), null).previousLevel()).isEqualTo("WARN");
        assertThat(overrides.active()).singleElement().satisfies(active -> {
            assertThat(active.level()).isEqualTo("TRACE");
            assertThat(active.operator()).isEqualTo("anonymous");
        });

        assertThat(overrides.revert(" com.example ", "bob")).isTrue();
        assertThat(logging.levels).containsEntry("com.example", LogLevel.WARN);
        assertThat(overrides.active()).isEmpty();
        assertThat(overrides.revert("com.example", "bob")).isFalse();
        assertThat(overrides.revert(null, "bob")).isFalse();
    }

    @Test
    void mapsRootAndRestoresAnInheritedLevel() {
        overrides.set("ROOT", "ERROR", Duration.ofSeconds(30), "ops");
        overrides.set("com.inherited", "DEBUG", Duration.ofSeconds(30), "ops");

        assertThat(logging.levels).containsEntry(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.ERROR);
        assertThat(overrides.active()).extracting(LogLevelOverrides.OverrideView::previousLevel).containsOnlyNulls();
        assertThat(overrides.revert("root", "ops")).isTrue();
        overrides.close();
        assertThat(logging.levels).containsEntry("com.inherited", null).containsEntry(LoggingSystem.ROOT_LOGGER_NAME, null);
        assertThatThrownBy(() -> overrides.set("com.inherited", "DEBUG", Duration.ofSeconds(1), "ops"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revertsAutomaticallyWhenTheDurationEnds() {
        logging.levels.put("com.example", LogLevel.INFO);
        LogLevelOverrides real = new LogLevelOverrides(logging, Duration.ofMinutes(1));
        try {
            real.set("com.example", "DEBUG", Duration.ofMillis(50), "ops");
            assertThat(logging.levels).containsEntry("com.example", LogLevel.DEBUG);
            await().atMost(Duration.ofSeconds(5)).until(() -> real.active().isEmpty());
            assertThat(logging.levels).containsEntry("com.example", LogLevel.INFO);
        } finally {
            real.close();
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> overrides.set("bad name", "DEBUG", Duration.ofMinutes(1), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overrides.set(null, "DEBUG", Duration.ofMinutes(1), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overrides.set("a", "LOUD", Duration.ofMinutes(1), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overrides.set("a", null, Duration.ofMinutes(1), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        logging.supported = EnumSet.of(LogLevel.INFO, LogLevel.ERROR);
        assertThatThrownBy(() -> overrides.set("a", "FATAL", Duration.ofMinutes(1), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overrides.set("a", "INFO", Duration.ofMinutes(11), "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overrides.set("a", "INFO", Duration.ZERO, "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overrides.set("a", "INFO", null, "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(logging.levels).isEmpty();
    }

    @Test
    void capsTheNumberOfOverridesAndClampsTheMaximumDuration() {
        for (int index = 0; index < LogLevelOverrides.MAX_OVERRIDES; index++) {
            overrides.set("logger" + index, "DEBUG", Duration.ofMinutes(1), "ops");
        }
        assertThatThrownBy(() -> overrides.set("one.more", "DEBUG", Duration.ofMinutes(1), "ops"))
                .isInstanceOf(IllegalStateException.class);
        // An already overridden logger can still be changed.
        assertThat(overrides.set("logger0", "TRACE", Duration.ofMinutes(1), "ops").level()).isEqualTo("TRACE");

        assertThat(new LogLevelOverrides(logging, Duration.ofHours(5)).maxDurationSeconds()).isEqualTo(3600);
        assertThat(new LogLevelOverrides(logging, null).maxDurationSeconds()).isEqualTo(3600);
        assertThat(new LogLevelOverrides(logging, Duration.ofSeconds(-1)).maxDurationSeconds()).isEqualTo(3600);
        assertThat(new LogLevelOverrides(logging, Duration.ofMillis(10)).maxDurationSeconds()).isEqualTo(1);
    }

    @Test
    void keepsGoingWhenRestoringFails() {
        overrides.set("com.example", "DEBUG", Duration.ofMinutes(1), "ops");
        logging.failOnSet = true;

        assertThat(overrides.revert("com.example", "ops")).isTrue();
        assertThat(overrides.active()).isEmpty();
    }

    /** Keeps levels in a map; a null level means the logger inherits its level. */
    static final class FakeLoggingSystem extends LoggingSystem {
        final Map<String, LogLevel> levels = new LinkedHashMap<>();
        Set<LogLevel> supported = EnumSet.allOf(LogLevel.class);
        boolean failOnSet;

        @Override
        public void beforeInitialize() {
        }

        @Override
        public Set<LogLevel> getSupportedLogLevels() {
            return supported;
        }

        @Override
        public void setLogLevel(String loggerName, LogLevel level) {
            if (failOnSet) throw new IllegalStateException("broken");
            levels.put(loggerName, level);
        }

        @Override
        public List<LoggerConfiguration> getLoggerConfigurations() {
            List<LoggerConfiguration> configurations = new ArrayList<>();
            levels.forEach((name, level) -> configurations.add(configuration(name, level)));
            return configurations;
        }

        @Override
        public LoggerConfiguration getLoggerConfiguration(String loggerName) {
            return levels.containsKey(loggerName) ? configuration(loggerName, levels.get(loggerName)) : null;
        }

        private static LoggerConfiguration configuration(String name, LogLevel level) {
            return new LoggerConfiguration(name, level, level == null ? LogLevel.INFO : level);
        }
    }
}
