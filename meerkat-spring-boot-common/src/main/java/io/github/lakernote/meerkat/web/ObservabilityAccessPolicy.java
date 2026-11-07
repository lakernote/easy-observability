package io.github.lakernote.meerkat.web;

/**
 * Authorizes requests to the dashboard API.
 * <p>Methods are invoked on the request thread, so implementations may read the Spring Security context or the
 * current servlet request. Define a bean of this type to replace the built-in policy, which allows viewing and
 * denies clearing, traffic capture and sensitive diagnostics.</p>
 */
@FunctionalInterface
public interface ObservabilityAccessPolicy {
    /** Whether the current request may read traces, metrics, storage status and permissions. */
    boolean canView();

    /**
     * Whether the current request may clear trace history. Only consulted after {@link #canView()} allows the
     * request. Denied unless overridden because clearing is destructive.
     */
    default boolean canClear() {
        return false;
    }

    /**
     * Whether the current request may start traffic capture and read captured records. Captured records contain
     * unredacted request and response bodies, SQL parameters and results, so this is a separate permission. Only
     * consulted after {@link #canView()} allows the request, and denied unless overridden (including by the built-in
     * policy); there is no separate switch, so granting this permission is what turns capture on.
     */
    default boolean canCapture() {
        return false;
    }

    /**
     * Whether the current request may run sensitive diagnostics: change log levels, take heap histograms, download
     * heap dumps and read configuration values. Only consulted after {@link #canView()} allows the request; denied
     * unless overridden (including by the built-in policy) because heap dumps and configuration expose secrets, heap
     * histograms and dumps pause the application, and log levels change runtime behaviour.
     */
    default boolean canOperate() {
        return false;
    }
}
