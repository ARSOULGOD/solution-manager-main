package com.solace.sm.cp.designer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.PackageModel;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The defaults-applied, normalized value building of {@code project()}'s pass 1 ("draft beats default", then
 * {@code normalize}), extracted unchanged from {@link DesignerProjectorImpl} (plan 011 P11-4.1, M-Q15) so the projection
 * and {@link MetricApplicabilityImpl} read one instance's draft the same way: a form control's {@code "true"} is a
 * bool, an empty string is "no value" (the default applies), and a value that does not bind is a bind error that leaves
 * the key unset. A predicate is evaluated over what this returns, never over the raw draft.
 */
final class DraftValueResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Spring's simple form: {@code <number>} (millis) or {@code <number><unit>}. */
    private static final Pattern SIMPLE_DURATION =
            Pattern.compile("^[-+]?\\d+(?:ns|us|ms|s|m|h|d)?$");

    /** ISO-8601, with the sections captured so emptiness can be rejected. */
    private static final Pattern ISO_DURATION =
            Pattern.compile("^[-+]?P(\\d+D)?(T(\\d+H)?(\\d+M)?(\\d+(?:\\.\\d+)?S)?)?$");

    /** 2^53 - 1: beyond this a JS number, and hence SM, is no longer exactly representable. */
    private static final BigInteger SAFE_MAX = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final BigInteger SAFE_MIN = SAFE_MAX.negate();

    private DraftValueResolver() {
    }

    /**
     * What one instance's fields resolve to.
     *
     * @param values     the value of every field that has one, by key
     * @param sources    {@code "draft"} or {@code "default"}, for each key in {@code values}
     * @param bindErrors the reason a draft value did not bind, by key; such a key is absent from {@code values}
     */
    record Resolved(Map<String, Object> values, Map<String, String> sources, Map<String, String> bindErrors) {
    }

    /** {@code project()}'s pass 1 for one component instance, moved here unchanged. */
    static Resolved resolve(List<PackageModel.PackageField> fields, Map<String, Object> instDraft) {
        return resolve(fields, instDraft, Map.of());
    }

    /**
     * Resolves the same package-declared connection bindings as the browser projector.
     * Catalog entries are deliberately kept as generic maps here because the projector port
     * exposes a generic SM-owned catalog rather than a connection-specific type.
     */
    static Resolved resolve(List<PackageModel.PackageField> fields, Map<String, Object> instDraft,
            Map<String, List<Object>> catalog) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, String> bindErrors = new LinkedHashMap<>();
        Map<String, Map<?, ?>> selected = selectedConnections(fields, instDraft, catalog);
        for (PackageModel.PackageField f : fields) {
            Object raw = instDraft.get(f.key());
            boolean hasRaw = raw != null && !"".equals(raw);
            if (hasRaw) {
                PackageModel.ValueFrom binding = f.valueFrom();
                String reference = binding == null ? null : connectionReference(raw);
                if (reference != null) {
                    NormalizeResult n = resolveReference(f, binding, reference, selected);
                    if (n.ok()) {
                        values.put(f.key(), n.value());
                        sources.put(f.key(), "connection");
                    } else {
                        bindErrors.put(f.key(), n.reason());
                    }
                    continue;
                }
                NormalizeResult n = normalize(f, raw);
                if (n.ok()) {
                    values.put(f.key(), n.value());
                    sources.put(f.key(), binding == null ? "draft" : "override");
                } else {
                    bindErrors.put(f.key(), n.reason());
                    // values left unset: absence and "set to undefined" read identically via .get()
                }
                continue;
            }

            PackageModel.ValueFrom binding = f.valueFrom();
            if (binding != null) {
                Map<?, ?> connection = selected.get(binding.ref());
                Object attributes = connection == null ? null : connection.get("attributes");
                Object secretAttributes = connection == null ? null : connection.get("secretAttributes");
                boolean secretAttribute = secretAttributes instanceof List<?> list
                        && list.stream().anyMatch(binding.attribute()::equals);
                if (!secretAttribute && attributes instanceof Map<?, ?> attributeMap) {
                    Object resolved = attributeMap.get(binding.attribute());
                    if (resolved != null && !"".equals(resolved)) {
                        NormalizeResult n = normalize(f, resolved);
                        if (n.ok()) {
                            values.put(f.key(), n.value());
                            sources.put(f.key(), "connection");
                            continue;
                        }
                        bindErrors.put(f.key(), n.reason());
                    }
                }
            }

            // Secret defaults are package metadata/placeholders, not credentials. The browser
            // deliberately projects secret fields as unset unless they carry a secret reference;
            // applying a literal package default here would make the server reject every such
            // field as SECRET_NOT_A_REFERENCE during stage validation.
            if (f.defaultValue() != null && !isSecret(f)) {
                values.put(f.key(), f.defaultValue());
                sources.put(f.key(), "default");
            }
        }
        return new Resolved(values, sources, bindErrors);
    }

    /**
     * Resolves the reference form emitted by the browser for a connection-backed field:
     * {@code {"$from":"<connection-id>#<attribute>"}}. The reference is deliberately
     * resolved against the selected connection in the catalog, rather than trusted as a
     * literal value from the client.
     */
    private static NormalizeResult resolveReference(PackageModel.PackageField field,
            PackageModel.ValueFrom binding, String reference, Map<String, Map<?, ?>> selected) {
        int separator = reference.lastIndexOf('#');
        if (separator <= 0 || separator == reference.length() - 1) {
            return NormalizeResult.fail("invalid connection reference");
        }
        String connectionId = reference.substring(0, separator);
        String attribute = reference.substring(separator + 1);
        if (!binding.attribute().equals(attribute)) {
            return NormalizeResult.fail("connection reference attribute does not match the package binding");
        }
        Map<?, ?> connection = selected.get(binding.ref());
        if (connection == null || !connectionId.equals(String.valueOf(connection.get("id")))) {
            return NormalizeResult.fail("connection reference does not match the selected connection");
        }
        Object secretAttributes = connection.get("secretAttributes");
        boolean secretAttribute = secretAttributes instanceof List<?> list
                && list.stream().anyMatch(attribute::equals);
        if (secretAttribute) {
            return NormalizeResult.fail("connection secret attributes cannot be projected");
        }
        Object attributes = connection.get("attributes");
        if (!(attributes instanceof Map<?, ?> attributeMap)) {
            return NormalizeResult.fail("selected connection has no attributes");
        }
        Object resolved = attributeMap.get(attribute);
        if (resolved == null || "".equals(resolved)) {
            return NormalizeResult.fail("selected connection attribute is not set");
        }
        return normalize(field, resolved);
    }

    private static String connectionReference(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.size() != 1) {
            return null;
        }
        Object reference = map.get("$from");
        return reference instanceof String value && !value.isBlank() ? value : null;
    }

    private static Map<String, Map<?, ?>> selectedConnections(List<PackageModel.PackageField> fields,
            Map<String, Object> instDraft, Map<String, List<Object>> catalog) {
        Map<String, Map<?, ?>> selected = new LinkedHashMap<>();
        List<Object> entries = catalog == null ? null : catalog.get("connection");
        if (entries == null) {
            return selected;
        }
        for (PackageModel.PackageField field : fields) {
            PackageModel.OptionsSource source = field.optionsSource();
            if (source == null || !"connection".equals(source.entity())) {
                continue;
            }
            Object picked = instDraft.get(field.key());
            if (picked == null || "".equals(picked)) {
                continue;
            }
            for (Object candidate : entries) {
                if (candidate instanceof Map<?, ?> map && String.valueOf(picked).equals(String.valueOf(map.get("id")))) {
                    selected.put(field.key(), map);
                    break;
                }
            }
        }
        return selected;
    }

    record NormalizeResult(boolean ok, Object value, String reason) {
        static NormalizeResult ok(Object v) {
            return new NormalizeResult(true, v, null);
        }

        static NormalizeResult fail(String reason) {
            return new NormalizeResult(false, null, reason);
        }
    }

    private static boolean isSecret(PackageModel.PackageField field) {
        return Boolean.TRUE.equals(field.secret()) || "secret".equals(field.type());
    }

    /**
     * Normalize a raw draft value to its declared type BEFORE predicates run. Deliberately
     * narrow: {@code "true"} normalizes for a bool because form controls emit strings, but
     * {@code "yes"} does not — UI-D2's grammar is strict equality.
     */
    static NormalizeResult normalize(PackageModel.PackageField f, Object raw) {
        if (raw == null || "".equals(raw)) {
            return NormalizeResult.ok(null);
        }
        switch (f.type()) {
            case "bool": {
                if (raw instanceof Boolean b) {
                    return NormalizeResult.ok(b);
                }
                if ("true".equals(raw)) {
                    return NormalizeResult.ok(Boolean.TRUE);
                }
                if ("false".equals(raw)) {
                    return NormalizeResult.ok(Boolean.FALSE);
                }
                return NormalizeResult.fail("expected a boolean, got " + jsonStringify(raw));
            }
            case "int": {
                BigDecimal n;
                if (raw instanceof Number num) {
                    n = new BigDecimal(num.toString());
                } else if (raw instanceof String s && s.matches("^[-+]?\\d+$")) {
                    n = new BigDecimal(s);
                } else {
                    return NormalizeResult.fail("expected an integer, got " + jsonStringify(raw));
                }
                BigDecimal stripped = n.stripTrailingZeros();
                if (stripped.scale() > 0) {
                    return NormalizeResult.fail("expected an integer, got " + jsonStringify(raw));
                }
                BigInteger bi = stripped.toBigInteger();
                if (bi.compareTo(SAFE_MAX) > 0 || bi.compareTo(SAFE_MIN) < 0) {
                    return NormalizeResult.fail(
                            "integer " + jsonStringify(raw) + " exceeds the exactly-representable range");
                }
                Object value = (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                        && bi.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0)
                        ? (Object) bi.intValue()
                        : (Object) bi.longValue();
                return NormalizeResult.ok(value);
            }
            case "duration": {
                if (raw instanceof String s && isDuration(s)) {
                    return NormalizeResult.ok(s);
                }
                return NormalizeResult.fail("expected a Spring Duration, got " + jsonStringify(raw));
            }
            default:
                return NormalizeResult.ok(raw);
        }
    }

    /**
     * Java's {@code Duration.parse} requires at least one section, and at least one section
     * after {@code T} if {@code T} is present — so bare {@code P} and {@code PT} are invalid.
     */
    private static boolean isDuration(String v) {
        if (SIMPLE_DURATION.matcher(v).matches()) {
            return true;
        }
        var m = ISO_DURATION.matcher(v);
        if (!m.matches()) {
            return false;
        }
        String days = m.group(1);
        String tMarker = m.group(2);
        String hours = m.group(3);
        String minutes = m.group(4);
        String seconds = m.group(5);
        if (tMarker != null && hours == null && minutes == null && seconds == null) {
            return false;
        }
        return days != null || hours != null || minutes != null || seconds != null;
    }

    static String jsonStringify(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unstringifiable value: " + v, e);
        }
    }
}
