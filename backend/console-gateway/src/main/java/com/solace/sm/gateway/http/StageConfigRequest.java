package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request body of {@code stageConfig} (console.yaml, {@code additionalProperties: false} on the body and on the
 * selection, instance and layout objects).
 *
 * <p>Structural faults - a missing field, a wrong type, a stray key - are {@code 400}, exactly as
 * {@link DeclareInstanceRequest}. Whether the DRAFT is acceptable is not decided here; it needs the package, and is a
 * {@code 422} ({@link StageRefusedException}).
 *
 * <p>⛔ There is no organization and no instance id: the tenant is the session's and the instance is the path's
 * ({@code I5}). {@code draftValues} may hold a secret FIELD only as a {@code secretRef://} reference; that is checked
 * against the package, not here.
 */
public record StageConfigRequest(Long baseStateVersion, ComponentSelection componentSelection,
        Map<String, Map<String, Object>> draftValues, List<Rendered> rendered) {

    public StageConfigRequest {
        if (baseStateVersion == null || baseStateVersion < 0) {
            throw new IllegalArgumentException("baseStateVersion is required and not negative");
        }
        if (componentSelection == null || draftValues == null || rendered == null) {
            throw new IllegalArgumentException("componentSelection, draftValues and rendered are required");
        }
        // Not Map.copyOf: it rejects a JSON null value and forgets the order the browser sent.
        draftValues = Collections.unmodifiableMap(new LinkedHashMap<>(draftValues));
        rendered = List.copyOf(rendered);
    }

    /** ⛔ I6: the draft may hold a literal the browser mistakenly typed into a secret field; it is never printed. */
    @Override
    public String toString() {
        return "StageConfigRequest[baseStateVersion=" + baseStateVersion + ", " + rendered.size()
                + " rendered entries]";
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    public record ComponentSelection(List<Instance> instances) {

        public ComponentSelection {
            if (instances == null) {
                throw new IllegalArgumentException("instances is required");
            }
            instances = List.copyOf(instances);
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    public record Instance(String instanceKey, String type, Layout layout) {

        public Instance {
            LoginRequest.requireLength(instanceKey, 1, Integer.MAX_VALUE, "instanceKey");
            LoginRequest.requireLength(type, 1, Integer.MAX_VALUE, "type");
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    /** UI-D20: presentation state. Excluded from the rendered set. */
    public record Layout(Number x, Number y) {

        public Layout {
            if (x == null || y == null) {
                throw new IllegalArgumentException("layout needs x and y");
            }
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    /** One entry of the client's own rendered claim (FE-D17). {@code value} is any JSON value. */
    public record Rendered(String instanceKey, String renderedPath, Object value) {

        public Rendered {
            LoginRequest.requireLength(instanceKey, 1, Integer.MAX_VALUE, "instanceKey");
            LoginRequest.requireLength(renderedPath, 1, Integer.MAX_VALUE, "renderedPath");
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }

        @Override
        public String toString() {
            return "Rendered[" + instanceKey + ", " + renderedPath + "]";
        }
    }
}
