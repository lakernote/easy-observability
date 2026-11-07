package io.github.lakernote.meerkat.integration.logging.internal;

import io.github.lakernote.meerkat.trace.internal.TraceLogs;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.ClassUtils;

/**
 * Hooks trace log buffering into whichever logging backend the application uses (Logback or Log4j2) once all
 * singletons exist, and removes it on shutdown. Does nothing with other backends.
 */
public final class TraceLogInstaller implements SmartInitializingSingleton, DisposableBean {
    private static final Log LOG = LogFactory.getLog(TraceLogInstaller.class);
    private final int maxPerTrace;
    private final int maxMessageChars;
    private final long maxBufferedBytes;
    private Installation installation;

    public TraceLogInstaller(int maxPerTrace, int maxMessageChars, long maxBufferedBytes) {
        this.maxPerTrace = maxPerTrace;
        this.maxMessageChars = maxMessageChars;
        this.maxBufferedBytes = maxBufferedBytes;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            installation = installBackend(TraceLogInstaller.class.getClassLoader());
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("Meerkat could not attach application logs to traces: " + failure);
        }
        if (installation != null) TraceLogs.enable(maxPerTrace, maxMessageChars, maxBufferedBytes);
    }

    static Installation installBackend(ClassLoader classLoader) {
        if (ClassUtils.isPresent("ch.qos.logback.classic.LoggerContext", classLoader)) {
            Installation logback = LogbackTraceLogs.installIfActive();
            if (logback != null) return logback;
        }
        if (ClassUtils.isPresent("org.apache.logging.log4j.core.LoggerContext", classLoader)) {
            return Log4j2TraceLogs.installIfActive();
        }
        return null;
    }

    boolean installed() {
        return installation != null;
    }

    @Override
    public void destroy() {
        TraceLogs.disable();
        if (installation == null) return;
        try {
            installation.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Logging may already be shut down.
        }
        installation = null;
    }

    interface Installation extends AutoCloseable {
        @Override
        void close();
    }
}
