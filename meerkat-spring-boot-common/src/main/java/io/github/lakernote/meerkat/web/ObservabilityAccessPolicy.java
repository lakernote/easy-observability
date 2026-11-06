package io.github.lakernote.meerkat.web;

/**
 * Authorizes requests to the dashboard API.
 * <p>Methods are invoked on the request thread, so implementations may read the Spring Security context or the
 * current servlet request. Define a bean of this type to replace the built-in policy, which allows viewing and
 * denies clearing.</p>
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
}
