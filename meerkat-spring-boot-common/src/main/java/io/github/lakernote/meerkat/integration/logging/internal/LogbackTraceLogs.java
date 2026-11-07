package io.github.lakernote.meerkat.integration.logging.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.github.lakernote.meerkat.trace.internal.TraceLogs;
import org.slf4j.LoggerFactory;

/**
 * Attaches a Logback appender to the root logger that copies each log line into the trace running on the logging
 * thread. Unsynchronized so it adds no lock to the application's logging path; it survives Logback resets, which
 * Spring Boot performs when it reconfigures logging.
 */
final class LogbackTraceLogs implements TraceLogInstaller.Installation {
    static final String APPENDER_NAME = "MEERKAT_TRACE_LOGS";
    private final LoggerContext context;
    private final TraceAppender appender = new TraceAppender();
    private final ResetListener resetListener = new ResetListener();

    private LogbackTraceLogs(LoggerContext context) {
        this.context = context;
    }

    /** Returns {@code null} when SLF4J is not bound to Logback. */
    static LogbackTraceLogs installIfActive() {
        return LoggerFactory.getILoggerFactory() instanceof LoggerContext context ? install(context) : null;
    }

    static LogbackTraceLogs install(LoggerContext context) {
        LogbackTraceLogs installation = new LogbackTraceLogs(context);
        installation.attach();
        context.addListener(installation.resetListener);
        return installation;
    }

    private void attach() {
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) == null) root.addAppender(appender);
    }

    @Override
    public void close() {
        context.removeListener(resetListener);
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
    }

    static final class TraceAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            if (!TraceLogs.wanted()) return;
            Level level = event.getLevel();
            TraceLogs.record(level == null ? "" : level.toString(), event.getLoggerName(),
                    message(event.getFormattedMessage(), event.getThrowableProxy()));
        }

        private static String message(String message, IThrowableProxy throwable) {
            if (throwable == null) return message;
            String failure = throwable.getClassName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return message == null || message.isEmpty() ? failure : message + " | " + failure;
        }
    }

    /** Re-attaches the appender after a reset, which detaches every appender. */
    private final class ResetListener implements LoggerContextListener {
        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onStart(LoggerContext context) {
        }

        @Override
        public void onReset(LoggerContext context) {
            attach();
        }

        @Override
        public void onStop(LoggerContext context) {
        }

        @Override
        public void onLevelChange(Logger logger, Level level) {
        }
    }
}
