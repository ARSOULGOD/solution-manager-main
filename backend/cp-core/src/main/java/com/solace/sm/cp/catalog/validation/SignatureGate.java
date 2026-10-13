package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Gate 2 — provenance. It checks PRESENCE, not a cryptographic signature (the Python's own scope,
 * {@code validate-package.py:26-34}); every package that passes is recorded {@code unsigned} by the caller
 * because nothing here verifies one (OR-40). Production runs with {@code devMode == false}.
 */
public final class SignatureGate {

    private SignatureGate() {
    }

    public static List<GateFailure> check(JsonNode pkg, boolean devMode) {
        JsonNode provenance = pkg.get("provenance");
        boolean none = !Py.truthy(provenance);
        String author = Py.text(provenance == null ? null : provenance.get("author"));
        if ("solace".equals(author) && !Py.truthy(provenance.get("signature"))) {
            return List.of(new GateFailure("2", "signature: author=solace but no signature"));
        }
        if ("dev-mode".equals(author) && !devMode) {
            return List.of(new GateFailure("2", "signature: unsigned dev-mode package but dev mode is off"));
        }
        if (none) {
            return List.of(new GateFailure("2", "signature: no provenance block"));
        }
        return List.of();
    }
}
