package io.github.lakernote.meerkat.integration.logging.internal;

import io.github.lakernote.meerkat.trace.internal.TraceLogs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Log4j2 counterpart of {@link LogbackTraceLogs}: adds an appender to the root logger and re-adds it whenever Log4j2
 * switches to a new configuration.
 */
final class Log4j2TraceLogs implements TraceLogInstaller.Installation {
    static final String APPENDER_NAME = "MEERKAT_TRACE_LOGS";
    private final LoggerContext context;
    private volatile TraceAppender appender;
    private final PropertyChangeListener configurationListener = this::configurationChanged;

    private Log4j2TraceLogs(LoggerContext context) {
        this.context = context;
    }

    /** Returns {@code null} when Log4j2 Core is not the active Log4j API implementation. */
    static Log4j2TraceLogs installIfActive() {
        return LogManager.getContext(false) instanceof LoggerContext context ? install(context) : null;
    }

    static Log4j2TraceLogs install(LoggerContext context) {
        Log4j2TraceLogs installation = new Log4j2TraceLogs(context);
        installation.attach(context.getConfiguration());
        context.addPropertyChangeListener(installation.configurationListener);
        return installation;
    }

    private void configurationChanged(PropertyChangeEvent event) {
        if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                && event.getNewValue() instanceof Configuration configuration) {
            attach(configuration);
        }
    }

    /** Each configuration gets its own appender because stopping the old configuration stops its appenders. */
    private void attach(Configuration configuration) {
        if (configuration.getRootLogger().getAppenders().containsKey(APPENDER_NAME)) return;
        TraceAppender fresh = new TraceAppender();
        fresh.start();
        configuration.addAppender(fresh);
        configuration.getRootLogger().addAppender(fresh, null, null);
        context.updateLoggers(configuration);
        appender = fresh;
    }

    @Override
    public void close() {
        context.removePropertyChangeListener(configurationListener);
        Configuration configuration = context.getConfiguration();
        configuration.getRootLogger().removeAppender(APPENDER_NAME);
        context.updateLoggers();
        TraceAppender current = appender;
        if (current != null) current.stop();
    }

    static final class TraceAppender extends AbstractAppender {
        TraceAppender() {
            super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (!TraceLogs.wanted()) return;
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                String failure = thrown.getClass().getName()
                        + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
                message = message.isEmpty() ? failure : message + " | " + failure;
            }
            TraceLogs.record(event.getLevel() == null ? "" : event.getLevel().name(), event.getLoggerName(), message);
        }
    }
}
