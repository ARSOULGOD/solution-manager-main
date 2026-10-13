package com.solace.sm.cp.api.designer;

import java.util.List;
import java.util.Map;

/**
 * Draft/observed input types, mirroring {@code contracts/view-models/src/types.ts} (state
 * half). See {@link PackageModel} for the no-Jackson-dependency rationale — every field name
 * here already matches its JSON key, so {@code cp-core} binds these by plain reflection with
 * no rename and no mixin needed.
 */
public final class StateModel {

    private StateModel() {
    }

    /** UI-D20: `layout` is presentation state and is excluded from `rendered` and the hash. */
    public record Layout(int x, int y) {
    }

    public record ComponentInstance(String instanceKey, String type, Layout layout) {
    }

    public record ComponentSelection(List<ComponentInstance> instances) {
    }

    /**
     * Draft values, namespaced per component instance: instanceKey -&gt; (fieldKey -&gt; rawValue).
     * A plain type alias in TS ({@code Record<string, Record<string, unknown>>}) — kept as a
     * bare generic {@code Map} here too, deliberately NOT wrapped in a record, because the
     * fixture JSON's {@code "draft"} key is itself the instance map with no enclosing object;
     * wrapping it would stop reflection-based binding from matching the JSON shape.
     */

    public record VersionObservation(String declared, String observed, String state) {
    }

    /** SM-D22 / architecture §6.1 — the five states are carried separately and never collapsed. */
    public record ConfigState(
            String desired,
            String rendered,
            String persisted,
            String restartObserved,
            String effectiveVerified) {
        public static final ConfigState EMPTY = new ConfigState(null, null, null, null, null);
    }

    public record ReplicaObservation(String id, String health) {
    }

    public record ObservedState(
            VersionObservation version,
            ConfigState config,
            List<ReplicaObservation> replicas,
            /** UI-D7: restart-observed config UNIONED with persisted virtual values, per instance. */
            Map<String, Map<String, Object>> restartObservedValues) {
    }

    /**
     * Options resolved from an SM-owned catalog (UI-D22). The package never supplies a URL.
     * Also a bare generic {@code Map} for the same reason as {@code DraftValues} above —
     * TS's {@code Partial<Record<entity, string[]>>} has no enclosing object either.
     */
}
