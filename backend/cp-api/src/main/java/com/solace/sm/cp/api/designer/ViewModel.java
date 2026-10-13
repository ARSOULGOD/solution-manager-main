package com.solace.sm.cp.api.designer;

import java.util.List;

/**
 * The Designer view-model output, mirroring {@code contracts/view-models/src/types.ts} (output
 * half) and implementing {@code docs/design/dynamic-ui/design.md} §6.5 (`UI-D15`) as corrected
 * against `DM-D*`/`FE-D*` — the same specification the TS `project()` implements ({@code OR-34}).
 *
 * <p>Every string-union field from the TS source (e.g. {@code ControlKind}, {@code Severity},
 * {@code ExclusionReason}) is carried here as a plain {@code String} rather than a Java {@code
 * enum} — several of the TS literal values (e.g. {@code "key-value"}, {@code "not-applicable"})
 * are not legal Java identifiers, and the fixtures pin the literal wire values, not a Java-side
 * vocabulary. See {@link PackageModel} for why this type carries no Jackson dependency.
 */
public record ViewModel(
        int engineVersion,
        String solution,
        /** {@code PackageVersionId | null} — a JSON number or string, or absent-as-null. */
        Object packageVersion,
        List<ComponentView> components,
        List<RenderedEntry> rendered,
        List<ExcludedEntry> excluded,
        List<MetricView> metrics,
        StatusView status,
        List<Diagnostic> diagnostics) {

    public static final int ENGINE_VERSION = 2;

    /**
     * FE-D23 / I6: no arm of this union can carry a resolved secret. A third arm appearing here
     * means I6 was breached upstream.
     */
    public sealed interface FieldValue {
        record Value(Object value, String source) implements FieldValue {
        }

        record Unset() implements FieldValue {
        }

        record SecretRef(String ref) implements FieldValue {
        }

        record SecretUnset() implements FieldValue {
        }
    }

    public record Validation(String severity, String code, String message) {
    }

    public record FieldView(
            String key,
            /** {@code null} when the field is {@code virtual} — it reaches no config file (UI-D24). */
            String renderedPath,
            String controlKind,
            FieldValue value,
            List<Object> options,
            boolean visible,
            boolean enabled,
            boolean virtual,
            boolean advanced,
            boolean masked,
            boolean discriminator,
            String label,
            String helperText,
            List<Validation> validations,
            /** Predicate keys that evaluated false. Empty when visible. */
            List<String> hiddenBy) {
    }

    public record ActionView(
            String key,
            String kind,
            boolean enabled,
            boolean visible,
            List<String> inputs,
            String label) {
    }

    public record SectionView(
            String title,
            int order,
            List<FieldView> fields,
            List<ActionView> actions,
            /** True when every visible field in the section is `advanced` — the section collapses by default. */
            boolean allVisibleFieldsAdvanced) {
    }

    public record ComponentView(
            String instanceKey,
            String type,
            String role,
            List<SectionView> sections) {
    }

    public record RenderedEntry(String instanceKey, String renderedPath, Object value) {
    }

    public record ExcludedEntry(String instanceKey, String key, String reason) {
    }

    public record MetricView(
            String instanceKey,
            String name,
            String metricType,
            String unit,
            String collectionState) {
    }

    public record VersionStatus(
            String state,
            String declared,
            String observed,
            boolean editingBlocked,
            String reason) {
    }

    public record StatusView(
            VersionStatus version,
            StateModel.ConfigState config,
            List<StateModel.ReplicaObservation> replicas,
            String healthRollup,
            String healthAvailability) {
    }

    public record Diagnostic(String code, String message, String instanceKey, String key) {
    }
}
