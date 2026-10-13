package com.solace.sm.cp.api.catalog;

import java.util.List;
import java.util.Objects;

/**
 * A package was refused before anything was written. Rendered {@code 422 VALIDATION_FAILED}, with a detail that
 * names the gate(s).
 *
 * <p>⛔ Carries gate ids and a fixed reason only, never a field value from the document: a package may carry a
 * secret-shaped default, and this reaches a response body ({@code I6}).
 */
public final class PackageRejectedException extends RuntimeException {

    /** The pseudo-gate id of a version collision, which is a catalog rule rather than one of the package gates. */
    public static final String VERSION_GATE = "version";

    private final List<String> gates;

    public PackageRejectedException(List<String> gates) {
        super("package refused by gate(s) " + String.join(", ", Objects.requireNonNull(gates, "gates")));
        if (gates.isEmpty()) {
            throw new IllegalArgumentException("a refusal names at least one gate");
        }
        this.gates = List.copyOf(gates);
    }

    /** The gate ids in the order they were reported ({@code "1"}, {@code "3"}, {@code "5b"}, {@code "version"}). */
    public List<String> gates() {
        return gates;
    }

    /** {@code (solution, packageVersion)} is already loaded with different content. */
    public static PackageRejectedException versionAlreadyLoaded() {
        return new PackageRejectedException(List.of(VERSION_GATE));
    }
}
