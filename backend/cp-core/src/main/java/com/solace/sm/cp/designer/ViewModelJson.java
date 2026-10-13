package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link ViewModel} into the exact plain-{@code Map}/{@code List} tree its JSON
 * fixture would parse to, so a golden test can compare it against the fixture's {@code
 * expected} node with ordinary {@code Object.equals} — no Jackson serialization of {@code
 * ViewModel} itself, because {@code cp-api} carries no Jackson dependency (see {@link
 * com.solace.sm.cp.api.designer.PackageModel}) and {@code FieldValue}'s sealed variants have no
 * {@code @JsonTypeInfo} to resolve them polymorphically even if it did.
 *
 * <p>The one thing this class has to get right that a generic serializer would not: TS omits an
 * optional (`field?:`) property from the JSON entirely when it is {@code undefined}, rather than
 * emitting {@code null} — but a handful of fields are declared non-optional yet nullable (for
 * example {@code ViewModel.packageVersion} and {@code FieldView.renderedPath}) and DO serialize
 * to an explicit {@code null}. Getting the wrong one wrong changes the object's key count, which
 * a {@code Map.equals} against the fixture will catch.
 */
final class ViewModelJson {

    private ViewModelJson() {
    }

    static Map<String, Object> of(ViewModel vm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engineVersion", vm.engineVersion());
        m.put("solution", vm.solution());
        m.put("packageVersion", vm.packageVersion()); // required, nullable — keep null
        m.put("components", list(vm.components(), ViewModelJson::of));
        m.put("rendered", list(vm.rendered(), ViewModelJson::of));
        m.put("excluded", list(vm.excluded(), ViewModelJson::of));
        m.put("metrics", list(vm.metrics(), ViewModelJson::of));
        m.put("status", of(vm.status()));
        m.put("diagnostics", list(vm.diagnostics(), ViewModelJson::of));
        return m;
    }

    private static Map<String, Object> of(ViewModel.ComponentView c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceKey", c.instanceKey());
        m.put("type", c.type());
        if (c.role() != null && !c.role().isBlank()) m.put("role", c.role());
        m.put("sections", list(c.sections(), ViewModelJson::of));
        return m;
    }

    private static Map<String, Object> of(ViewModel.SectionView s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", s.title());
        m.put("order", s.order());
        m.put("fields", list(s.fields(), ViewModelJson::of));
        m.put("actions", list(s.actions(), ViewModelJson::of));
        m.put("allVisibleFieldsAdvanced", s.allVisibleFieldsAdvanced());
        return m;
    }

    private static Map<String, Object> of(ViewModel.FieldView f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", f.key());
        m.put("renderedPath", f.renderedPath()); // required, nullable — keep null
        m.put("controlKind", f.controlKind());
        m.put("value", of(f.value()));
        putIfNotNull(m, "options", f.options());
        m.put("visible", f.visible());
        m.put("enabled", f.enabled());
        m.put("virtual", f.virtual());
        m.put("advanced", f.advanced());
        m.put("masked", f.masked());
        m.put("discriminator", f.discriminator());
        putIfNotNull(m, "label", f.label());
        putIfNotNull(m, "helperText", f.helperText());
        m.put("validations", list(f.validations(), ViewModelJson::of));
        m.put("hiddenBy", new ArrayList<Object>(f.hiddenBy()));
        return m;
    }

    private static Map<String, Object> of(ViewModel.FieldValue v) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (v instanceof ViewModel.FieldValue.Value val) {
            m.put("kind", "value");
            m.put("value", val.value());
            m.put("source", val.source());
        } else if (v instanceof ViewModel.FieldValue.Unset) {
            m.put("kind", "unset");
        } else if (v instanceof ViewModel.FieldValue.SecretRef ref) {
            m.put("kind", "secretRef");
            m.put("ref", ref.ref());
        } else if (v instanceof ViewModel.FieldValue.SecretUnset) {
            m.put("kind", "secretUnset");
        } else {
            throw new IllegalStateException("Unknown FieldValue variant: " + v);
        }
        return m;
    }

    private static Map<String, Object> of(ViewModel.Validation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", v.severity());
        m.put("code", v.code());
        m.put("message", v.message());
        return m;
    }

    private static Map<String, Object> of(ViewModel.ActionView a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", a.key());
        m.put("kind", a.kind());
        // visible BEFORE enabled — the order project.ts builds the ActionView literal in, which is
        // what the fixtures' bytes carry (types.ts declares them the other way round).
        m.put("visible", a.visible());
        m.put("enabled", a.enabled());
        m.put("inputs", new ArrayList<Object>(a.inputs()));
        putIfNotNull(m, "label", a.label());
        return m;
    }

    private static Map<String, Object> of(ViewModel.RenderedEntry r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceKey", r.instanceKey());
        m.put("renderedPath", r.renderedPath());
        m.put("value", r.value());
        return m;
    }

    private static Map<String, Object> of(ViewModel.ExcludedEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceKey", e.instanceKey());
        m.put("key", e.key());
        m.put("reason", e.reason());
        return m;
    }

    private static Map<String, Object> of(ViewModel.MetricView m0) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceKey", m0.instanceKey());
        m.put("name", m0.name());
        m.put("metricType", m0.metricType());
        m.put("unit", m0.unit());
        m.put("collectionState", m0.collectionState());
        return m;
    }

    private static Map<String, Object> of(ViewModel.StatusView s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", of(s.version()));
        m.put("config", of(s.config()));
        m.put("replicas", list(s.replicas(), ViewModelJson::of));
        m.put("healthRollup", s.healthRollup());
        m.put("healthAvailability", s.healthAvailability());
        return m;
    }

    private static Map<String, Object> of(ViewModel.VersionStatus v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", v.state());
        putIfNotNull(m, "declared", v.declared());
        putIfNotNull(m, "observed", v.observed());
        m.put("editingBlocked", v.editingBlocked());
        putIfNotNull(m, "reason", v.reason());
        return m;
    }

    private static Map<String, Object> of(StateModel.ConfigState c) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfNotNull(m, "desired", c.desired());
        putIfNotNull(m, "rendered", c.rendered());
        putIfNotNull(m, "persisted", c.persisted());
        putIfNotNull(m, "restartObserved", c.restartObserved());
        putIfNotNull(m, "effectiveVerified", c.effectiveVerified());
        return m;
    }

    private static Map<String, Object> of(StateModel.ReplicaObservation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("health", r.health());
        return m;
    }

    private static Map<String, Object> of(ViewModel.Diagnostic d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", d.code());
        m.put("message", d.message());
        putIfNotNull(m, "instanceKey", d.instanceKey());
        putIfNotNull(m, "key", d.key());
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static <T> List<Object> list(List<T> items, java.util.function.Function<T, Object> f) {
        List<Object> out = new ArrayList<>(items.size());
        for (T t : items) {
            out.add(f.apply(t));
        }
        return out;
    }
}
