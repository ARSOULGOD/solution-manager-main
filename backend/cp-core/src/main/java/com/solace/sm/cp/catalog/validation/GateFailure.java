package com.solace.sm.cp.catalog.validation;

/**
 * One refused gate. {@code gate} is the Python gate's own id without the {@code gate} prefix ({@code "1"},
 * {@code "5b"}, {@code "14"}). {@code message} names structure only — component type, field key, path,
 * keyword — and NEVER a field value: a package may carry a secret-shaped default, and a gate message reaches
 * logs and HTTP responses (invariant {@code I6}).
 */
public record GateFailure(String gate, String message) {
}
