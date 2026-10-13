package com.solace.sm.launcher;

/**
 * Safe diagnostics for a failure: the class of its root cause, never the message (a driver message can echo SQL
 * values). Deliberately free of {@code java.sql} types - the launcher may not touch data access.
 */
final class FailureDiagnostics {
    private FailureDiagnostics() {
    }

    static String of(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "root=" + root.getClass().getName();
    }
}
