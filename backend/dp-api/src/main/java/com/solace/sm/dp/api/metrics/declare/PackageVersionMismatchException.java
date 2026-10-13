package com.solace.sm.dp.api.metrics.declare;

/**
 * A declare named a package version the data plane already holds, with a different content digest (plan 011 M-Q5).
 * A loaded package version is immutable, so this fails closed: nothing is written. Carries nothing but the class.
 */
public final class PackageVersionMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PackageVersionMismatchException() {
        super("package version content differs from what this data plane already holds for that version");
    }
}
