package com.solace.sm.cp.api.designer;

import java.util.List;
import java.util.Map;

/**
 * Solution Package input types, mirroring {@code contracts/view-models/src/types.ts} (input
 * half) which mirrors {@code contracts/solution-package.schema.json} — that schema is
 * authoritative; these are a convenience for {@link DesignerProjector}, not a second
 * definition ({@code OR-34}, satisfies {@code D4}).
 *
 * <p><b>No Jackson dependency here on purpose</b> — {@code cp-api}'s pom carries no production
 * dependencies by design. JSON binding for these records happens entirely in {@code cp-core}
 * (which already depends on {@code jackson-databind}), by reflection over these plain records;
 * Jackson's record support resolves component names without needing an annotation on the type
 * being bound, as long as the JSON key matches the component name. The one exception —
 * {@code default} is a Java keyword and cannot be a component name — is handled by renaming
 * that JSON key to {@code defaultValue} before conversion (see {@code PackageJsonReader} in
 * cp-core), not by annotating this type.
 *
 * <p>Every optional field is {@code null} when absent — never a sentinel — matching the TS
 * `field?:` shape read back from JSON, where an absent key deserializes to {@code null}.
 */
public final class PackageModel {

    private PackageModel() {
    }

    /**
     * UI-D22: options resolved from an SM-owned catalog. The package never supplies a URL.
     * {@code connectionType} (UI-D27) optionally narrows a {@code connection} catalog to one type.
     */
    public record OptionsSource(String kind, String entity, String connectionType) {
    }

    public record UiHints(
            String label,
            String section,
            String subSection,
            Integer order,
            Boolean advanced,
            String helperText,
            Boolean readOnly) {
    }

    /** UI-D28: a field value derived from the selected SM catalog entry. */
    public record ValueFrom(String ref, String attribute) {
    }

    public record Column(String key, String type) {
    }

    public record PackageField(
            String key,
            String type,
            List<Object> version,
            Boolean virtual,
            List<Object> options,
            OptionsSource optionsSource,
            List<Column> columns,
            Object defaultValue,
            Boolean required,
            Boolean secret,
            Boolean discriminator,
            /** UI-D2: an object of field:literal, implicitly AND-ed. No OR, comparison or negation. */
            Map<String, Object> when,
            Double min,
            Double max,
            String pattern,
            String yamlSubtree,
            UiHints ui,
            ValueFrom valueFrom) {

        /** Backward-compatible constructor for callers that do not model bindings. */
        public PackageField(
                String key,
                String type,
                List<Object> version,
                Boolean virtual,
                List<Object> options,
                OptionsSource optionsSource,
                List<Column> columns,
                Object defaultValue,
                Boolean required,
                Boolean secret,
                Boolean discriminator,
                Map<String, Object> when,
                Double min,
                Double max,
                String pattern,
                String yamlSubtree,
                UiHints ui) {
            this(key, type, version, virtual, options, optionsSource, columns, defaultValue, required, secret,
                    discriminator, when, min, max, pattern, yamlSubtree, ui, null);
        }
    }

    public record PackageAction(
            String key,
            String kind,
            String entity,
            List<String> inputs,
            Map<String, Object> when,
            UiHints ui) {
    }

    public record PackageMetric(
            String name,
            String metricType,
            String unit,
            String layer,
            Map<String, Object> when,
            Map<String, Object> source,
            /** OQ-7 (b): {@code null} means "true" (the metric is in the derived default layer); {@code false} opts it out. */
            Boolean defaultChart) {
    }

    public record HealthBlock(Map<String, Object> probe) {
    }

    public record PackageComponent(
            String type,
            String cardinality,
            List<Object> version,
            String role,
            String configPath,
            String configFile,
            String loggerPrefix,
            List<PackageField> fields,
            List<PackageAction> actions,
            List<PackageMetric> metrics,
            HealthBlock health,
            Map<String, Object> logs,
            /**
             * OQ-1 (a), M-Q9: an http_json read of the component's process start time, used in direct mode to detect a
             * restart ({@code kind}, {@code path}, {@code port}, {@code pointer}, {@code onUnavailable}); {@code null}
             * when the package declares none (restart detection then degrades to {@code inferred}).
             */
            Map<String, Object> restartSignal) {
    }

    public record Provenance(String author, String signature) {
    }

    public record SolutionPackage(
            String solution,
            List<Object> packageVersion,
            String appliesTo,
            Provenance provenance,
            List<PackageComponent> components) {
    }
}
