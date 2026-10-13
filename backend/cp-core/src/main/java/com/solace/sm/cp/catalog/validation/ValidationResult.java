package com.solace.sm.cp.catalog.validation;

import java.util.List;

/** The ordered failures of one package, in the order {@code validate-package.py} reports them. */
public record ValidationResult(List<GateFailure> failures) {

    public ValidationResult {
        failures = List.copyOf(failures);
    }

    public boolean ok() {
        return failures.isEmpty();
    }

    /** The first failure's gate id, which is the only label the Python prints under {@code --expect-fail}. */
    public String firstGate() {
        return failures.isEmpty() ? null : failures.get(0).gate();
    }
}
