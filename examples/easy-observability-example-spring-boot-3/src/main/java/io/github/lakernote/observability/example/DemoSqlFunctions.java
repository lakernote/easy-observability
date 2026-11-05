package io.github.lakernote.observability.example;

/** H2 user functions registered in {@code schema.sql}, so a demo SQL statement can really take a long time. */
public final class DemoSqlFunctions {
    private DemoSqlFunctions() {
    }

    /** {@code select demo_sleep(?)}: blocks the JDBC call for the given milliseconds. */
    public static long sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return millis;
    }
}
