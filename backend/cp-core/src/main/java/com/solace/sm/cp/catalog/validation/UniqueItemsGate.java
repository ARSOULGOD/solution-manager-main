package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of gate 1 the schema library cannot see: {@code uniqueItems} on the three version arrays
 * ({@code packageVersion}, a component's {@code version}, a field's {@code version}).
 *
 * <p>The Python's {@code jsonschema} compares array elements by Python {@code ==}, under which {@code 1 == 1.0};
 * networknt compares {@code JsonNode.equals}, under which an int node and a double node are different, so
 * {@code [1, 1.0]} passed gate 1 in Java and was stored as {@code [1,1]} (S3 review). Run after the schema gate
 * has passed, so the arrays are known to be arrays of version ids. Reported as {@code "1"}, naming the location
 * and the keyword and never a value, like every other gate-1 failure.
 */
final class UniqueItemsGate {

    private UniqueItemsGate() {
    }

    static List<GateFailure> check(JsonNode pkg) {
        List<GateFailure> failures = new ArrayList<>();
        check(pkg.get("packageVersion"), "/packageVersion", failures);
        JsonNode components = pkg.get("components");
        if (components != null && components.isArray()) {
            for (int c = 0; c < components.size(); c++) {
                JsonNode component = components.get(c);
                check(component.get("version"), "/components/" + c + "/version", failures);
                JsonNode fields = component.get("fields");
                if (fields != null && fields.isArray()) {
                    for (int f = 0; f < fields.size(); f++) {
                        check(fields.get(f).get("version"), "/components/" + c + "/fields/" + f + "/version",
                                failures);
                    }
                }
            }
        }
        return failures;
    }

    private static void check(JsonNode array, String location, List<GateFailure> failures) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            for (int j = i + 1; j < array.size(); j++) {
                if (Py.eq(array.get(i), array.get(j))) {
                    failures.add(new GateFailure("1", "schema: " + location + ": violates 'uniqueItems'"));
                    return;
                }
            }
        }
    }
}
