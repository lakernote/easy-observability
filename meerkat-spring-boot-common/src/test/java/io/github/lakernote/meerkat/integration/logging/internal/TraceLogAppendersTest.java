package io.github.lakernote.meerkat.integration.logging.internal;

import ch.qos.logback.classic.Logger;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceLog;
import io.github.lakernote.meerkat.trace.internal.TraceLogs;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLogAppendersTest {
    @AfterEach
    void reset() {
        TraceContext.clear();
        TraceLogs.disable();
    }

    @Test
    void logbackAppenderCopiesLinesAndSurvivesReset() {
        ch.qos.logback.classic.LoggerContext context = new ch.qos.logback.classic.LoggerContext();
        context.start();
        LogbackTraceLogs installation = LogbackTraceLogs.install(context);
        TraceLogs.enable(10, 200, 1_000_000);
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.INFO);
        Logger logger = context.getLogger("com.example.Orders");
        try {
            List<TraceLog> logs = inSlowTrace(() -> {
                logger.info("loading {}", 7);
                logger.error("failed", new IllegalStateException("boom"));
                logger.error("", new IllegalArgumentException());
                logger.debug("below the default level");
            });
            assertThat(logs).extracting(TraceLog::message)
                    .containsExactly("loading 7", "failed | java.lang.IllegalStateException: boom",
                            "java.lang.IllegalArgumentException");
            assertThat(logs.get(0).level()).isEqualTo("INFO");
            assertThat(logs.get(0).logger()).isEqualTo("com.example.Orders");

            context.reset();
            assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(LogbackTraceLogs.APPENDER_NAME))
                    .isNotNull();
            assertThat(inSlowTrace(() -> logger.warn("after reset"))).extracting(TraceLog::message)
                    .containsExactly("after reset");
        } finally {
            installation.close();
        }
        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(LogbackTraceLogs.APPENDER_NAME)).isNull();
        assertThat(inSlowTrace(() -> logger.warn("after close"))).isEmpty();
        context.stop();
    }

    @Test
    void logbackAppenderSkipsWorkOutsideTraces() {
        ch.qos.logback.classic.LoggerContext context = new ch.qos.logback.classic.LoggerContext();
        context.start();
        LogbackTraceLogs installation = LogbackTraceLogs.install(context);
        TraceLogs.enable(10, 200, 1_000_000);
        context.getLogger("x").info("no trace");
        installation.close();
        context.stop();
    }

    @Test
    void log4j2AppenderCopiesLinesAndSurvivesReconfiguration() {
        LoggerContext context = new LoggerContext("meerkat-test");
        context.start(new DefaultConfiguration());
        Log4j2TraceLogs installation = Log4j2TraceLogs.install(context);
        TraceLogs.enable(10, 200, 1_000_000);
        org.apache.logging.log4j.Logger logger = context.getLogger("com.example.Orders");
        try {
            List<TraceLog> logs = inSlowTrace(() -> {
                logger.error("loading {}", 7);
                logger.error("failed", new IllegalStateException("boom"));
                logger.error("", new IllegalArgumentException());
                logger.info("below the default level");
                assertThat(logger.isEnabled(Level.INFO)).isFalse();
            });
            assertThat(logs).extracting(TraceLog::message)
                    .containsExactly("loading 7", "failed | java.lang.IllegalStateException: boom",
                            "java.lang.IllegalArgumentException");
            assertThat(logs.get(0).level()).isEqualTo("ERROR");

            context.setConfiguration(new DefaultConfiguration());
            assertThat(context.getConfiguration().getRootLogger().getAppenders())
                    .containsKey(Log4j2TraceLogs.APPENDER_NAME);
            assertThat(inSlowTrace(() -> logger.error("after reconfigure"))).extracting(TraceLog::message)
                    .containsExactly("after reconfigure");
        } finally {
            installation.close();
        }
        assertThat(context.getConfiguration().getRootLogger().getAppenders())
                .doesNotContainKey(Log4j2TraceLogs.APPENDER_NAME);
        context.stop();
    }

    @Test
    void installerEnablesBufferingForTheActiveBackendAndUndoesItOnDestroy() {
        TraceLogInstaller installer = new TraceLogInstaller(5, 100, 1_000_000);
        installer.afterSingletonsInstantiated();
        try {
            assertThat(installer.installed()).isTrue();
            TraceContext.startRoot("GET /x", SpanKind.HTTP);
            assertThat(TraceLogs.wanted()).isTrue();
            TraceContext.clear();
        } finally {
            installer.destroy();
        }
        assertThat(installer.installed()).isFalse();
        installer.destroy();
    }

    @Test
    void installerDoesNothingWithoutASupportedBackend() {
        ClassLoader bootstrapOnly = new ClassLoader(null) { };
        assertThat(TraceLogInstaller.installBackend(bootstrapOnly)).isNull();
    }

    private static List<TraceLog> inSlowTrace(Runnable body) {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        body.run();
        TraceContext.markSlow("SLOW");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.stopRoot(Long.MAX_VALUE, "test", null, completion::set);
        return completion.get().logs().stream().filter(line -> !"OMITTED".equals(line.level())).toList();
    }
}
