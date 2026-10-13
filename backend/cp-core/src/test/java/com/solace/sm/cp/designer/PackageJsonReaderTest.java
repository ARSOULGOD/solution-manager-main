package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.designer.PackageModel;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PackageJsonReader}'s one special case: {@code default} is a Java keyword, so the
 * raw JSON tree gets {@code components[*].fields[*].default} renamed to {@code defaultValue}
 * before binding — and, critically, {@code fields[*].default} ONLY. A field's own {@code when}
 * predicate is an arbitrary {@code fieldKey -> literal} map (schema {@code $defs/predicate}), so
 * a package that happens to declare a field literally named {@code "default"} can have another
 * field's {@code when} reference it as {@code {"default": <literal>}} — that key must survive
 * untouched, or the predicate silently starts gating on the wrong (nonexistent) field.
 */
class PackageJsonReaderTest {

    @Test
    void rendersFieldsDefaultKeyToDefaultValue() throws IOException {
        String json = """
                {
                  "solution": "reader-fixture",
                  "packageVersion": [1],
                  "appliesTo": ">=1.0",
                  "components": [
                    {
                      "type": "widget",
                      "cardinality": "exactly-one",
                      "configPath": "widget",
                      "fields": [
                        { "key": "port", "type": "int", "default": 8080 }
                      ]
                    }
                  ]
                }
                """;

        PackageModel.SolutionPackage pkg = PackageJsonReader.read(json);

        PackageModel.PackageField port = pkg.components().get(0).fields().get(0);
        assertThat(port.key()).isEqualTo("port");
        assertThat(port.defaultValue()).isEqualTo(8080);
    }

    @Test
    void doesNotRenameADefaultKeyInsideAWhenPredicateEvenWhenItGatesOnAFieldNamedDefault() throws IOException {
        // Two fields: one is itself named "default" (a legal, if unusual, field key), and a
        // second field's "when" predicate gates on it — so the predicate's own JSON object has
        // a key literally "default" that must NOT be touched by the fields[*].default rename.
        String json = """
                {
                  "solution": "reader-fixture-escape",
                  "packageVersion": [1],
                  "appliesTo": ">=1.0",
                  "components": [
                    {
                      "type": "widget",
                      "cardinality": "exactly-one",
                      "configPath": "widget",
                      "fields": [
                        { "key": "default", "type": "bool" },
                        { "key": "gatedField", "type": "string", "when": { "default": true } }
                      ]
                    }
                  ]
                }
                """;

        PackageModel.SolutionPackage pkg = PackageJsonReader.read(json);

        java.util.List<PackageModel.PackageField> fields = pkg.components().get(0).fields();
        PackageModel.PackageField namedDefault = fields.get(0);
        PackageModel.PackageField gated = fields.get(1);

        // The field literally named "default" must still be named "default", not renamed away
        // (the rename only ever touches the JSON KEY "default", never a VALUE, but this pins
        // that the field's own `key` property was never in scope for the rename either).
        assertThat(namedDefault.key()).isEqualTo("default");
        assertThat(namedDefault.defaultValue()).isNull();

        // The predicate must still be keyed "default" (referencing the field above) — if the
        // buggy global-recursive rename were in effect, this map would instead carry the key
        // "defaultValue" and evaluate() would silently gate on a field that does not exist,
        // making the predicate never match (always hidden or always visible, depending on
        // direction) instead of tracking the "default" field's actual value.
        assertThat(gated.when()).containsExactlyEntriesOf(java.util.Map.of("default", true));
    }
}
