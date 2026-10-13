package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gate 1 — the package against {@code solution-package.schema.json} (JSON Schema draft 2020-12). The Python
 * carries gates 6, 7, 8 and 11 inside the same schema, so they are reported here as {@code "1"} too — its only
 * schema label ({@code validate-package.py:22}).
 *
 * <p>The schema is supplied by the caller (this module mirrors, it does not own it — {@code BE-D10}); the
 * registry is built with remote fetching off, so a {@code $ref} can never reach the network from a load.
 *
 * <p>The library's own message is deliberately NOT used: it may quote the offending instance value, and a
 * package may carry a secret-shaped default ({@code I6}). A failure names the instance location and the
 * keyword that refused it, nothing else.
 */
public final class SchemaGate {

    private final Schema schema;

    public SchemaGate(JsonNode schemaDocument) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
        this.schema = registry.getSchema(schemaDocument);
    }

    public List<GateFailure> check(JsonNode pkg) {
        List<Error> errors = schema.validate(pkg);
        List<GateFailure> failures = new ArrayList<>(errors.size());
        errors.stream()
                .sorted(Comparator.comparing((Error e) -> e.getInstanceLocation().toString())
                        .thenComparing(e -> String.valueOf(e.getKeyword())))
                .forEach(e -> failures.add(new GateFailure("1",
                        "schema: " + e.getInstanceLocation() + ": violates '" + e.getKeyword() + "'")));
        return failures;
    }
}
