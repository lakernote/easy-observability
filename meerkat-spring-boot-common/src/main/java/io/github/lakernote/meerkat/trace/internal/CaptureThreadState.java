package io.github.lakernote.meerkat.trace.internal;

/**
 * Thread-bound hand-offs between capture hooks that cannot see each other's span directly.
 * <ul>
 *     <li>Outbound HTTP: the observation handler installs the remote call's target while its scope is open, and the
 *     client interceptor claims it once.</li>
 *     <li>JDBC: the SQL listener remembers which statement a captured span belongs to so result rows read later,
 *     for example through {@code Statement.getResultSet()}, land on the right SQL span.</li>
 * </ul>
 */
public final class CaptureThreadState {
    private static final ThreadLocal<CaptureTarget> REMOTE = new ThreadLocal<>();
    private static final ThreadLocal<PendingResult> PENDING_RESULT = new ThreadLocal<>();

    private CaptureThreadState() {
    }

    /** Installs {@code target} as the open remote call and returns the previously installed one. */
    public static CaptureTarget openRemote(CaptureTarget target) {
        CaptureTarget previous = REMOTE.get();
        REMOTE.set(target);
        return previous;
    }

    public static void restoreRemote(CaptureTarget previous) {
        if (previous == null) REMOTE.remove();
        else REMOTE.set(previous);
    }

    /** Returns the open remote call's target once, so nested or repeated interceptors record it only once. */
    public static CaptureTarget claimRemote() {
        CaptureTarget target = REMOTE.get();
        if (target == null) return null;
        REMOTE.remove();
        return target.capturing() ? target : null;
    }

    public static void rememberStatement(Object statement, CaptureTarget target) {
        if (statement == null || target == null) PENDING_RESULT.remove();
        else PENDING_RESULT.set(new PendingResult(statement, target));
    }

    /** Returns the capturing SQL span that executed {@code statement}, or {@code null}. */
    public static CaptureTarget statementTarget(Object statement) {
        PendingResult pending = PENDING_RESULT.get();
        if (pending == null) return null;
        if (!pending.target.capturing()) {
            PENDING_RESULT.remove();
            return null;
        }
        return pending.statement == statement ? pending.target : null;
    }

    /** Whether any SQL span on this thread may still receive result rows; avoids a driver call otherwise. */
    public static boolean hasPendingStatement() {
        return PENDING_RESULT.get() != null;
    }

    /**
     * Drops the remembered statement once the thread leaves its trace, so pooled threads do not keep the statement
     * and the captured trace reachable.
     */
    static void forgetStatement() {
        if (PENDING_RESULT.get() != null) PENDING_RESULT.remove();
    }

    public static void clear() {
        REMOTE.remove();
        PENDING_RESULT.remove();
    }

    private record PendingResult(Object statement, CaptureTarget target) { }
}
