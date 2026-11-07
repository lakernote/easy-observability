package io.github.lakernote.meerkat.diagnostics.internal;

/** Normalizes the operator recorded in the audit log and shown on the dashboard. */
final class Operators {
    private Operators() {
    }

    static String safe(String operator) {
        if (operator == null || operator.isBlank()) return "anonymous";
        String cleaned = operator.replaceAll("[\\r\\n\\t]", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
