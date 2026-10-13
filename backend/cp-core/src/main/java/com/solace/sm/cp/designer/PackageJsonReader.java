package com.solace.sm.cp.designer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.PackageModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Solution Package fixture (or, later, a stored package) into {@link
 * PackageModel.SolutionPackage} — the one hop where {@code cp-core} does the JSON binding that
 * {@code cp-api}'s dependency-free records cannot do for themselves (see the class javadoc on
 * {@link PackageModel}).
 *
 * <p>Every JSON key in the schema already matches a Java record component name, with one
 * exception: {@code default} is a Java keyword. Rather than annotate the shared type (which
 * would pull Jackson into {@code cp-api}), this reader renames that one key to {@code
 * defaultValue} on the raw tree before binding — the only special case needed.
 *
 * <p>The rename is deliberately targeted at exactly {@code components[*].fields[*].default} —
 * the one place the schema declares that key — rather than a blind recursive walk renaming any
 * map key literally named {@code "default"} anywhere in the tree. A field's own {@code when}
 * predicate (schema {@code $defs/predicate}) is itself an arbitrary {@code fieldKey -> literal}
 * map, so if some other declared field is named {@code "default"}, a predicate referencing it —
 * e.g. {@code "when": {"default": true}} — carries a JSON key {@code "default"} that must NOT be
 * renamed; a global recursive rename would corrupt that predicate's key and silently change
 * which field it gates on.
 */
public final class PackageJsonReader {

    // Fixtures carry informational keys the core contract type does not model (e.g. the
    // engine-edge fixture's "authoring" note) — the schema, not this reader, is the strict
    // gate on package shape (contracts/solution-package.schema.json); this reader tolerates
    // extras rather than re-enforcing that gate a second time.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PackageJsonReader() {
    }

    public static PackageModel.SolutionPackage read(String json) throws IOException {
        Object tree = MAPPER.readValue(json, Object.class);
        Object renamed = renameDefaultKeyInFields(tree);
        return MAPPER.convertValue(renamed, PackageModel.SolutionPackage.class);
    }

    public static PackageModel.SolutionPackage readFile(Path path) throws IOException {
        return read(Files.readString(path));
    }

    /**
     * Walks only the schema's known {@code components[*].fields[*]} shape and renames {@code
     * default} to {@code defaultValue} on each field map — nowhere else. Every other subtree
     * (each field's own {@code when}, {@code ui}, {@code optionsSource}, {@code columns}; each
     * component's {@code actions}, {@code metrics}, {@code health}, {@code logs}; the package's
     * own top-level keys) is copied through untouched, keys and all.
     */
    private static Object renameDefaultKeyInFields(Object root) {
        if (!(root instanceof Map<?, ?> rootMap)) {
            return root;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rootMap.entrySet()) {
            String key = (String) e.getKey();
            Object value = e.getValue();
            out.put(key, "components".equals(key) ? renameInComponentsList(value) : value);
        }
        return out;
    }

    private static Object renameInComponentsList(Object componentsValue) {
        if (!(componentsValue instanceof List<?> components)) {
            return componentsValue;
        }
        List<Object> out = new ArrayList<>(components.size());
        for (Object component : components) {
            out.add(renameInComponent(component));
        }
        return out;
    }

    private static Object renameInComponent(Object componentValue) {
        if (!(componentValue instanceof Map<?, ?> componentMap)) {
            return componentValue;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : componentMap.entrySet()) {
            String key = (String) e.getKey();
            Object value = e.getValue();
            out.put(key, "fields".equals(key) ? renameInFieldsList(value) : value);
        }
        return out;
    }

    private static Object renameInFieldsList(Object fieldsValue) {
        if (!(fieldsValue instanceof List<?> fields)) {
            return fieldsValue;
        }
        List<Object> out = new ArrayList<>(fields.size());
        for (Object field : fields) {
            out.add(renameDefaultKeyOnFieldMap(field));
        }
        return out;
    }

    private static Object renameDefaultKeyOnFieldMap(Object fieldValue) {
        if (!(fieldValue instanceof Map<?, ?> fieldMap)) {
            return fieldValue;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : fieldMap.entrySet()) {
            String key = (String) e.getKey();
            // Renamed on this map only — its own nested "when" (or any other sub-map) is copied
            // as the untouched value it already is, not recursed into.
            out.put("default".equals(key) ? "defaultValue" : key, e.getValue());
        }
        return out;
    }
}
