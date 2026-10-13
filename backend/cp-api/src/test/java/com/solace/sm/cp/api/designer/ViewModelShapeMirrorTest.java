package com.solace.sm.cp.api.designer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locks the Java view-model records to the TS engine's output types, read from
 * {@code contracts/view-models/src/types.ts} at test time ({@code OR-34}).
 *
 * <p><b>Why read the file rather than hard-code the property names.</b> A hard-coded list is a
 * second copy of the shape, and two copies drift. With the TS file as the single source this test
 * is a mirror: a {@code readonly} property added to {@code ViewModel}, {@code FieldView},
 * {@code RenderedEntry}, {@code StatusView}, {@code Diagnostic} (or any other output interface
 * below) without the matching record component in {@link ViewModel} fails here, rather than
 * surfacing as a field the Java projector silently never emits. The reverse direction is also
 * asserted — a Java component with no TS property is a field the TS engine does not have.
 *
 * <p>Scope: the interfaces listed in {@code INTERFACES}/{@code NESTED}, every property being
 * {@code readonly}, and the {@code FieldValue} union's arms. A new output interface not listed
 * there, or an inline type nested deeper than one level, is outside what this reads.
 *
 * <p>The golden test over the shared fixtures cannot stand in for this: a fixture only pins the
 * properties the fixtures happen to exercise.
 */
class ViewModelShapeMirrorTest {

    /** Top-level TS output interface name to the Java record that mirrors it. */
    private static final Map<String, Class<?>> INTERFACES = new LinkedHashMap<>();

    /**
     * Inline object types nested one level inside an output interface ({@code Interface.property})
     * to the named Java record that mirrors them.
     */
    private static final Map<String, Class<?>> NESTED = new LinkedHashMap<>();

    static {
        INTERFACES.put("ViewModel", ViewModel.class);
        INTERFACES.put("FieldView", ViewModel.FieldView.class);
        INTERFACES.put("RenderedEntry", ViewModel.RenderedEntry.class);
        INTERFACES.put("StatusView", ViewModel.StatusView.class);
        INTERFACES.put("Diagnostic", ViewModel.Diagnostic.class);
        INTERFACES.put("Validation", ViewModel.Validation.class);
        INTERFACES.put("ActionView", ViewModel.ActionView.class);
        INTERFACES.put("SectionView", ViewModel.SectionView.class);
        INTERFACES.put("ComponentView", ViewModel.ComponentView.class);
        INTERFACES.put("ExcludedEntry", ViewModel.ExcludedEntry.class);
        INTERFACES.put("MetricView", ViewModel.MetricView.class);
        // Emitted under StatusView.config; the same interface is the input half's ConfigState.
        INTERFACES.put("ConfigState", StateModel.ConfigState.class);

        NESTED.put("StatusView.version", ViewModel.VersionStatus.class);
        NESTED.put("StatusView.replicas", StateModel.ReplicaObservation.class);
    }

    /** A property declaration; {@code readonly} is captured (not required) so its absence is seen. */
    private static final Pattern PROPERTY = Pattern.compile("(readonly\\s+)?(\\w+)\\??\\s*:");

    @Test
    void everyTypesTsOutputPropertyHasARecordComponentOfTheSameName() throws IOException {
        Map<String, Set<String>> ts = parseInterfaces(readTypesTs());

        for (Map.Entry<String, Class<?>> e : INTERFACES.entrySet()) {
            assertThat(ts).as("types.ts must declare `interface %s`", e.getKey()).containsKey(e.getKey());
            assertThat(components(e.getValue()))
                    .as("record components of %s must be exactly the readonly properties of TS "
                            + "`interface %s` — a field added to the TS engine needs its Java twin "
                            + "(fix the port, not this test)", e.getValue().getSimpleName(), e.getKey())
                    .containsExactlyInAnyOrderElementsOf(ts.get(e.getKey()));
        }
    }

    @Test
    void everyNestedInlineObjectTypeHasARecordOfTheSameShape() throws IOException {
        Map<String, Set<String>> ts = parseInterfaces(readTypesTs());

        for (Map.Entry<String, Class<?>> e : NESTED.entrySet()) {
            assertThat(ts).as("types.ts must declare an inline object type at %s", e.getKey())
                    .containsKey(e.getKey());
            assertThat(components(e.getValue()))
                    .as("record components of %s must be exactly the readonly properties of the "
                            + "inline object type at TS %s", e.getValue().getSimpleName(), e.getKey())
                    .containsExactlyInAnyOrderElementsOf(ts.get(e.getKey()));
        }
    }

    /**
     * The mirror's regex used to REQUIRE {@code readonly}, so a property declared without it fell
     * out of the scan and the mirror stayed green over a field it never saw. Every property of a
     * TS output type is {@code readonly}; one that is not is a shape the parser cannot vouch for.
     */
    @Test
    void everyPropertyOfAnOutputTypeIsDeclaredReadonly() throws IOException {
        Set<String> mutable = new LinkedHashSet<>();
        parseInterfaces(readTypesTs(), mutable);
        assertThat(mutable)
                .as("properties declared without `readonly` — the mirror would not count them")
                .isEmpty();
    }

    private static final Pattern FIELD_VALUE_TYPE =
            Pattern.compile("export\\s+type\\s+FieldValue\\s*=((?:\\s*\\|\\s*\\{[^}]*\\})+)\\s*;");
    private static final Pattern ARM = Pattern.compile("\\{([^}]*)\\}");
    private static final Pattern ARM_PROPERTY = Pattern.compile("readonly\\s+(\\w+)\\??\\s*:\\s*([^;]+)");

    /**
     * FE-D23 / I6: {@code FieldValue} is a {@code type} union, not an interface, so the interface
     * mirror never saw it — a fifth arm such as {@code { kind: 'resolved'; plaintext }} (which
     * {@code types.ts} says means I6 was breached) left the mirror green. Its arms are therefore
     * parsed here and compared with the sealed interface's permitted records, kind for kind and
     * component for component.
     */
    @Test
    void theFieldValueUnionHasExactlyTheArmsOfTheSealedInterface() throws IOException {
        Matcher union = FIELD_VALUE_TYPE.matcher(stripComments(readTypesTs()));
        assertThat(union.find()).as("types.ts must declare `export type FieldValue = | {...} | ...;`").isTrue();

        Map<String, Set<String>> tsArms = new LinkedHashMap<>();
        Matcher arm = ARM.matcher(union.group(1));
        while (arm.find()) {
            String kind = null;
            Set<String> props = new LinkedHashSet<>();
            Matcher p = ARM_PROPERTY.matcher(arm.group(1));
            while (p.find()) {
                if (p.group(1).equals("kind")) {
                    kind = p.group(2).trim().replace("'", "");
                } else {
                    props.add(p.group(1));
                }
            }
            assertThat(kind).as("every FieldValue arm carries a `kind` discriminator").isNotNull();
            tsArms.put(kind, props);
        }

        Map<String, Set<String>> javaArms = new LinkedHashMap<>();
        for (Class<?> permitted : ViewModel.FieldValue.class.getPermittedSubclasses()) {
            String simple = permitted.getSimpleName();
            javaArms.put(Character.toLowerCase(simple.charAt(0)) + simple.substring(1), components(permitted));
        }
        // The Java `Value` record carries `source`; the TS arm carries `value` and `source` too.
        assertThat(javaArms)
                .as("the arms (kind -> payload properties) of TS `FieldValue` must equal the "
                        + "permitted records of ViewModel.FieldValue — a new arm is an I6 question "
                        + "before it is a port question")
                .isEqualTo(tsArms);
    }

    /**
     * Guards the guard: if the parser silently found nothing (a TS reformat that defeats the
     * regex), the two tests above would compare against empty sets and fail — but a rename of an
     * interface in TS must also fail loudly rather than skip, so every mapped name is pinned.
     */
    @Test
    void theParserSeesTheInterfacesItIsMeantToMirror() throws IOException {
        Map<String, Set<String>> ts = parseInterfaces(readTypesTs());
        assertThat(ts.keySet()).containsAll(INTERFACES.keySet());
        assertThat(ts.get("ViewModel")).contains("engineVersion", "components", "diagnostics");
        assertThat(ts.get("StatusView.version")).contains("state", "editingBlocked");
    }

    private static Set<String> components(Class<?> record) {
        return new LinkedHashSet<>(Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
    }

    private static String readTypesTs() throws IOException {
        Path fromModule = Path.of("../../contracts/view-models/src/types.ts");
        Path file = Files.exists(fromModule) ? fromModule : Path.of("contracts/view-models/src/types.ts");
        assertThat(file)
                .as("contracts/view-models/src/types.ts must be readable — it is the authority "
                        + "this test mirrors")
                .exists();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /**
     * Collects the {@code readonly} property names of every {@code export interface}, keyed by the
     * interface name, and of every inline object type one level inside it (keyed
     * {@code Interface.property}). Comments are stripped first so a brace in a doc comment cannot
     * unbalance the scan.
     */
    static Map<String, Set<String>> parseInterfaces(String source) {
        return parseInterfaces(source, new LinkedHashSet<>());
    }

    /** As above; every property declared WITHOUT {@code readonly} is added to {@code mutable}. */
    static Map<String, Set<String>> parseInterfaces(String source, Set<String> mutable) {
        String code = stripComments(source);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher header = Pattern.compile("export\\s+interface\\s+(\\w+)[^{]*\\{").matcher(code);
        while (header.find()) {
            String name = header.group(1);
            Set<String> top = new LinkedHashSet<>();
            out.put(name, top);

            int depth = 1;
            int i = header.end();
            // Lexical position at which the current depth-1 property started, to attribute depth-2
            // object types to the property that opened them.
            String currentProperty = null;
            StringBuilder statement = new StringBuilder();
            Set<String> nested = null;
            while (depth > 0 && i < code.length()) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                    if (depth == 2 && currentProperty != null) {
                        nested = new LinkedHashSet<>();
                        out.put(name + "." + currentProperty, nested);
                    }
                    statement.setLength(0);
                } else if (c == '}') {
                    depth--;
                    statement.setLength(0);
                    if (depth == 1) {
                        nested = null;
                    }
                } else if (c == ';' || c == ',' || c == '\n') {
                    statement.setLength(0);
                } else {
                    statement.append(c);
                    Matcher m = PROPERTY.matcher(statement);
                    if (m.find()) {
                        String where = depth == 1 ? name : name + "." + currentProperty;
                        if (m.group(1) == null) {
                            mutable.add(where + "." + m.group(2));
                        }
                        if (depth == 1) {
                            top.add(m.group(2));
                            currentProperty = m.group(2);
                        } else if (depth == 2 && nested != null) {
                            nested.add(m.group(2));
                        }
                        statement.setLength(0);
                    }
                }
                i++;
            }
        }
        return out;
    }
}
