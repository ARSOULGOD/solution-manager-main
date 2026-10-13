package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The load-time gates for one Solution Package, in the Python's order ({@code validate-package.py:validate}):
 * gate 1 (schema) first and alone — "later gates assume a well-formed document" — then gate 2 (provenance) and
 * the semantic gates 4, 5, 5b, 9, 10, 12, 13, 14, 15. Gate 3 (overlap across a catalog entry's packages) is
 * {@link OverlapGate}, because it needs the other packages.
 *
 * <p>This is the SECOND implementation of these gates. The Python ({@code contracts/validation}) stays the
 * normative one until the parity tests pass; they run it as an oracle and are the proof, not this comment.
 * Consumer-side load validation only: {@code BE-D10}'s "mirror-and-lock, no codegen, no producer-side runtime
 * validation" is unaffected.
 *
 * <p>Instances are immutable after construction and safe to share across threads.
 */
public final class PackageValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final SchemaGate schemaGate;

    public PackageValidator(JsonNode schema) {
        this.schemaGate = new SchemaGate(schema);
    }

    /** {@code devMode} is false in production: a {@code dev-mode} package is then refused by gate 2. */
    public ValidationResult validate(JsonNode pkg, boolean devMode) {
        List<GateFailure> schemaFailures = new ArrayList<>(schemaGate.check(pkg));
        if (schemaFailures.isEmpty()) {
            schemaFailures.addAll(UniqueItemsGate.check(pkg));
        }
        if (!schemaFailures.isEmpty()) {
            return new ValidationResult(schemaFailures);
        }
        List<GateFailure> failures = new ArrayList<>(SignatureGate.check(pkg, devMode));
        failures.addAll(SemanticGates.check(pkg));
        return new ValidationResult(failures);
    }

    /** Parses first. A syntax error is a gate-1 refusal that does not quote the document (the parser would). */
    public ValidationResult validateJson(String json, boolean devMode) {
        JsonNode pkg;
        try {
            pkg = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return new ValidationResult(List.of(new GateFailure("1", "schema: <root>: document is not valid JSON")));
        }
        if (pkg == null || pkg.isMissingNode()) {
            return new ValidationResult(List.of(new GateFailure("1", "schema: <root>: document is empty")));
        }
        return validate(pkg, devMode);
    }
}
