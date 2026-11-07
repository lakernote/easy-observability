package io.github.lakernote.meerkat.diagnostics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Temporary log levels that revert by themselves, through Spring Boot's {@link LoggingSystem} so they work with
 * Logback and Log4j2. Expiry runs on one daemon thread that is created on the first change. Every change and revert
 * is written to the application log as an audit trail.
 */
public final class LogLevelOverrides implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LogLevelOverrides.class);
    public static final Duration MAX_DURATION = Duration.ofHours(1);
    static final int MAX_OVERRIDES = 50;
    static final int MAX_LOGGERS = 500;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_$.\\-]{1,256}");
    private final LoggingSystem loggingSystem;
    private final Duration maxDuration;
    private final Clock clock;
    private final Map<String, TemporaryLevel> overrides = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;
    private boolean closed;

    public LogLevelOverrides(LoggingSystem loggingSystem, Duration maxDuration) {
        this(loggingSystem, maxDuration, Clock.systemUTC());
    }

    LogLevelOverrides(LoggingSystem loggingSystem, Duration maxDuration, Clock clock) {
        this.loggingSystem = loggingSystem;
        this.maxDuration = maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                || maxDuration.compareTo(MAX_DURATION) > 0 ? MAX_DURATION : maxDuration;
        this.clock = clock;
    }

    public long maxDurationSeconds() {
        return Math.max(1, maxDuration.toSeconds());
    }

    /** Levels the logging system accepts, most verbose first. */
    public List<String> levels() {
        List<String> levels = new ArrayList<>();
        for (LogLevel level : LogLevel.values()) {
            if (loggingSystem.getSupportedLogLevels().contains(level)) levels.add(level.name());
        }
        return levels;
    }

    /** Loggers whose name contains {@code query} (case-insensitive), at most {@code limit} of them. */
    public LoggerList loggers(String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int max = Math.max(1, Math.min(MAX_LOGGERS, limit));
        Map<String, Instant> active = new HashMap<>();
        for (OverrideView view : active()) active.put(view.logger(), view.expiresAt());
        List<LoggerView> loggers = new ArrayList<>();
        int matched = 0;
        for (LoggerConfiguration configuration : loggingSystem.getLoggerConfigurations()) {
            String name = configuration.getName();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            matched++;
            if (loggers.size() >= max) continue;
            loggers.add(new LoggerView(name, levelName(configuration.getConfiguredLevel()),
                    levelName(configuration.getEffectiveLevel()), active.get(name)));
        }
        return new LoggerList(matched, loggers);
    }

    /**
     * Sets {@code level} on {@code logger} for {@code duration}, after which the level configured before the first
     * override is restored. Changing an overridden logger again keeps that original level and restarts the timer.
     *
     * @throws IllegalArgumentException for an invalid logger name, level or duration
     * @throws IllegalStateException when {@link #MAX_OVERRIDES} overrides are already active
     */
    public synchronized OverrideView set(String logger, String level, Duration duration, String operator) {
        String name = loggerName(logger);
        LogLevel target = level(level);
        if (duration == null || duration.toMillis() < 1 || duration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException("Duration must be between 1 second and " + maxDurationSeconds() + " seconds");
        }
        if (closed) throw new IllegalStateException("The application is shutting down");
        TemporaryLevel existing = overrides.get(name);
        if (existing == null && overrides.size() >= MAX_OVERRIDES) {
            throw new IllegalStateException("At most " + MAX_OVERRIDES + " temporary log levels may be active");
        }
        LogLevel previous = existing != null ? existing.previous : configuredLevel(name);
        loggingSystem.setLogLevel(name, target);
        if (existing != null) existing.future.cancel(false);
        Instant now = clock.instant();
        TemporaryLevel override =
                new TemporaryLevel(name, previous, target, Operators.safe(operator), now, now.plus(duration));
        overrides.put(name, override);
        override.future = scheduler().schedule(() -> expire(override), duration.toMillis(), TimeUnit.MILLISECONDS);
        log.warn("Meerkat set log level of {} to {} for {}s by {}; it reverts to {} afterwards", name, target,
                duration.toSeconds(), override.operator, previous == null ? "inherited" : previous);
        return override.view(now);
    }

    /** Restores the original level of {@code logger} now; false when it has no active override. */
    public synchronized boolean revert(String logger, String operator) {
        if (logger == null) return false;
        String name = logger.trim();
        TemporaryLevel override = overrides.get("root".equalsIgnoreCase(name) ? LoggingSystem.ROOT_LOGGER_NAME : name);
        if (override == null) return false;
        override.future.cancel(false);
        restore(override, "reverted by " + Operators.safe(operator));
        return true;
    }

    public synchronized List<OverrideView> active() {
        Instant now = clock.instant();
        List<OverrideView> views = new ArrayList<>(overrides.size());
        for (TemporaryLevel override : overrides.values()) views.add(override.view(now));
        return views;
    }

    /** Restores every original level and stops the expiry thread. */
    @Override
    public synchronized void close() {
        closed = true;
        for (TemporaryLevel override : new ArrayList<>(overrides.values())) {
            override.future.cancel(false);
            restore(override, "application shutdown");
        }
        if (scheduler != null) scheduler.shutdownNow();
    }

    private synchronized void expire(TemporaryLevel override) {
        if (overrides.get(override.logger) == override) restore(override, "expired");
    }

    private void restore(TemporaryLevel override, String reason) {
        overrides.remove(override.logger);
        try {
            loggingSystem.setLogLevel(override.logger, override.previous);
            log.warn("Meerkat restored log level of {} to {} ({})", override.logger,
                    override.previous == null ? "inherited" : override.previous, reason);
        } catch (RuntimeException failure) {
            log.warn("Meerkat could not restore log level of {} ({})", override.logger, reason, failure);
        }
    }

    private LogLevel configuredLevel(String name) {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    private ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "meerkat-log-level-revert");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            scheduler = executor;
        }
        return scheduler;
    }

    private static String loggerName(String logger) {
        String name = logger == null ? "" : logger.trim();
        if ("root".equalsIgnoreCase(name)) return LoggingSystem.ROOT_LOGGER_NAME;
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid logger name");
        return name;
    }

    private LogLevel level(String level) {
        try {
            LogLevel parsed = LogLevel.valueOf(level == null ? "" : level.trim().toUpperCase(Locale.ROOT));
            if (loggingSystem.getSupportedLogLevels().contains(parsed)) return parsed;
        } catch (IllegalArgumentException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException("Unsupported log level: " + level);
    }

    private static String levelName(LogLevel level) {
        return level == null ? null : level.name();
    }

    /** {@code overriddenUntil} is set while a temporary level is active. */
    public record LoggerView(String name, String configuredLevel, String effectiveLevel, Instant overriddenUntil) { }

    public record LoggerList(int total, List<LoggerView> loggers) { }

    /** {@code previousLevel} is null when the logger inherited its level before the override. */
    public record OverrideView(String logger, String level, String previousLevel, String operator, Instant startedAt,
                               Instant expiresAt, long remainingSeconds) { }

    private static final class TemporaryLevel {
        private final String logger;
        private final LogLevel previous;
        private final LogLevel level;
        private final String operator;
        private final Instant startedAt;
        private final Instant expiresAt;
        private ScheduledFuture<?> future;

        private TemporaryLevel(String logger, LogLevel previous, LogLevel level, String operator, Instant startedAt,
                         Instant expiresAt) {
            this.logger = logger;
            this.previous = previous;
            this.level = level;
            this.operator = operator;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
        }

        OverrideView view(Instant now) {
            long remaining = Math.max(0, Duration.between(now, expiresAt).toMillis() + 999) / 1000;
            return new OverrideView(logger, level.name(), levelName(previous), operator, startedAt, expiresAt, remaining);
        }
    }
}
