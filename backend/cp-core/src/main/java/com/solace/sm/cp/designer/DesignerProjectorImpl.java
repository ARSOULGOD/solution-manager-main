package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Java {@code project()} port ({@code OR-34}), line-for-line faithful to {@code
 * contracts/view-models/src/project.ts} — the two must agree on the 33 shared fixtures under
 * {@code contracts/view-models/fixtures/viewmodel/}, proved by {@code
 * DesignerProjectorFixtureTest}.
 *
 * <p>Every comment tagged with a TS source line range below quotes the semantics being ported,
 * not a paraphrase — where a choice was not obvious from the TS alone, the reasoning is spelled
 * out rather than left implicit.
 *
 * <p>No Spring wiring here on purpose — the engine stays a plain, dependency-free port until
 * plan-010 wires it behind an endpoint.
 */
public final class DesignerProjectorImpl implements DesignerProjector {

    private static final Map<String, String> CONTROL = Map.of(
            "string", "text",
            "int", "number",
            "bool", "switch",
            "enum", "select",
            "duration", "duration",
            "list", "string-list",
            "map", "key-value",
            "table", "table",
            "yaml", "yaml-editor",
            "secret", "secret");

    private static final Pattern SECRET_REF = Pattern.compile("^secretRef://");

    private static final Pattern YAML_SECRET_SCAN =
            Pattern.compile("(?:password|secret|token|apikey|api_key)\\s*[:=]", Pattern.CASE_INSENSITIVE);

    @Override
    public ViewModel project(
            PackageModel.SolutionPackage pkg,
            StateModel.ComponentSelection selection,
            Map<String, Map<String, Object>> draft,
            StateModel.ObservedState observed,
            Map<String, List<Object>> catalog,
            Object requestedPackageVersion) {

        Map<String, List<Object>> cat = catalog == null ? Map.of() : catalog;
        Map<String, Map<String, Object>> draftMap = draft == null ? Map.of() : draft;

        List<ViewModel.Diagnostic> diagnostics = new ArrayList<>();
        StateModel.VersionObservation vo = observed.version();
        boolean versionBlocks = "VERSION_DRIFT".equals(vo.state()) || "VERSION_UNKNOWN".equals(vo.state());
        String reason = null;
        if (versionBlocks) {
            reason = "VERSION_DRIFT".equals(vo.state())
                    ? "Declared " + orQuestionMark(vo.declared()) + " but observed " + orQuestionMark(vo.observed()) + "."
                    : "No loaded package covers the declared version.";
        }
        ViewModel.VersionStatus versionStatus =
                new ViewModel.VersionStatus(vo.state(), vo.declared(), vo.observed(), versionBlocks, reason);
        ViewModel.StatusView status = new ViewModel.StatusView(
                versionStatus,
                observed.config() != null ? observed.config() : StateModel.ConfigState.EMPTY,
                observed.replicas() != null ? observed.replicas() : List.of(),
                "NOT_APPLICABLE",
                "not-declared");

        if (pkg == null) {
            return new ViewModel(
                    ViewModel.ENGINE_VERSION, "", null, List.of(), List.of(), List.of(), List.of(),
                    status,
                    List.of(new ViewModel.Diagnostic(
                            "VERSION_UNKNOWN",
                            "No package resolved for the declared version; nothing can be rendered.",
                            null, null)));
        }

        Object packageVersion = requestedPackageVersion != null
                ? requestedPackageVersion
                : lastOf(pkg.packageVersion());
        if (packageVersion == null || !containsStrict(pkg.packageVersion(), packageVersion)) {
            return new ViewModel(
                    ViewModel.ENGINE_VERSION, pkg.solution(), packageVersion, List.of(), List.of(), List.of(), List.of(),
                    status,
                    List.of(new ViewModel.Diagnostic(
                            "PACKAGE_VERSION_NOT_DECLARED",
                            "Package version " + packageVersion + " is not declared by this package; nothing can be rendered.",
                            null, null)));
        }

        Map<String, PackageModel.PackageComponent> byType = new LinkedHashMap<>();
        for (PackageModel.PackageComponent c : pkg.components()) {
            if (inVersion(c.version(), packageVersion)) {
                byType.put(c.type(), c); // last declaration wins, matching the TS Map constructor
            }
        }

        List<ViewModel.ComponentView> components = new ArrayList<>();
        List<ViewModel.RenderedEntry> rendered = new ArrayList<>();
        List<ViewModel.ExcludedEntry> excluded = new ArrayList<>();
        List<ViewModel.MetricView> metrics = new ArrayList<>();
        boolean anyHealthDeclared = false;

        for (StateModel.ComponentInstance inst : selection.instances()) {
            PackageModel.PackageComponent comp = byType.get(inst.type());
            if (comp == null) {
                diagnostics.add(new ViewModel.Diagnostic(
                        "UNKNOWN_COMPONENT",
                        "Selection names component type " + inst.type() + " which the package does not declare.",
                        inst.instanceKey(), null));
                continue;
            }
            if (comp.health() != null) {
                anyHealthDeclared = true;
            }

            List<PackageModel.PackageField> fields = new ArrayList<>();
            if (comp.fields() != null) {
                for (PackageModel.PackageField f : comp.fields()) {
                    if (inVersion(f.version(), packageVersion)) {
                        fields.add(f);
                    }
                }
            }
            Map<String, Object> instDraft = draftMap.getOrDefault(inst.instanceKey(), Map.of());

            // ---- pass 1: resolve values (draft beats default), record bind errors. Extracted unchanged to
            // DraftValueResolver (P11-4.1), which the metric applicability evaluates over too.
            DraftValueResolver.Resolved resolved = DraftValueResolver.resolve(fields, instDraft, cat);
            Map<String, Object> values = resolved.values();
            Map<String, String> sources = resolved.sources();
            Map<String, String> bindErrors = resolved.bindErrors();

            // orphan draft keys are preserved in the draft but never rendered
            Set<String> declaredKeys = new LinkedHashSet<>();
            for (PackageModel.PackageField f : fields) {
                declaredKeys.add(f.key());
            }
            for (String k : instDraft.keySet()) {
                if (!declaredKeys.contains(k)) {
                    excluded.add(new ViewModel.ExcludedEntry(inst.instanceKey(), k, "orphan"));
                    diagnostics.add(new ViewModel.Diagnostic(
                            "ORPHAN_DRAFT_KEY",
                            "Draft holds " + k + ", which the package does not declare.",
                            inst.instanceKey(), k));
                }
            }

            // ---- pass 2: visibility, then the field views
            Map<String, Bucket> sectionsMap = new LinkedHashMap<>();
            int[] sectionSeq = {0};

            for (int idx = 0; idx < fields.size(); idx++) {
                PackageModel.PackageField f = fields.get(idx);
                List<String> hiddenBy = WhenEvaluator.evaluate(f.when(), values);
                boolean visible = hiddenBy.isEmpty();
                boolean virtual = Boolean.TRUE.equals(f.virtual());
                boolean secret = isSecret(f);
                Object raw = values.get(f.key());
                String src = sources.get(f.key());

                ViewModel.FieldValue value;
                boolean secretMalformed = false;
                if (secret) {
                    if (raw == null) {
                        value = new ViewModel.FieldValue.SecretUnset();
                    } else if (SECRET_REF.matcher(String.valueOf(raw)).find()) {
                        value = new ViewModel.FieldValue.SecretRef(String.valueOf(raw));
                    } else {
                        secretMalformed = true;
                        value = new ViewModel.FieldValue.SecretUnset();
                    }
                } else if (raw == null) {
                    value = new ViewModel.FieldValue.Unset();
                } else {
                    value = new ViewModel.FieldValue.Value(raw, src != null ? src : "draft");
                }

                List<Object> opts = f.options();
                if (opts == null && f.optionsSource() != null) {
                    // Mirrors contracts/view-models project.ts: the option LIST is the catalog entries' ids
                    // (the draft holds ids, and enum validation compares against them), optionally narrowed
                    // to one connectionType (UI-D27). An unsupplied catalog stays null → CATALOG_UNRESOLVED.
                    opts = optionIds(cat.get(f.optionsSource().entity()), f.optionsSource().connectionType());
                }
                if (f.optionsSource() != null && opts == null) {
                    diagnostics.add(new ViewModel.Diagnostic(
                            "CATALOG_UNRESOLVED",
                            "Options for " + f.key() + " come from the " + f.optionsSource().entity()
                                    + " catalog, which was not supplied.",
                            inst.instanceKey(), f.key()));
                }

                String renderedPath = virtual ? null : renderedPathFor(comp.configPath(), f.key());
                List<ViewModel.Validation> validations;
                if (!visible) {
                    validations = List.of();
                } else if (secretMalformed) {
                    validations = List.of(new ViewModel.Validation(
                            "block", "SECRET_NOT_A_REFERENCE",
                            "A secret field accepts only a secretRef:// reference. A literal value would be "
                                    + "persisted, diffed and exported in clear (I6)."));
                } else {
                    validations = bindAndSemanticValidations(f, raw, bindErrors.get(f.key()));
                }

                ViewModel.FieldView view = new ViewModel.FieldView(
                        f.key(),
                        renderedPath,
                        secret ? "secret" : CONTROL.get(f.type()),
                        value,
                        opts,
                        visible,
                        !versionBlocks && !Boolean.TRUE.equals(f.ui() != null ? f.ui().readOnly() : null),
                        virtual,
                        f.ui() != null && Boolean.TRUE.equals(f.ui().advanced()),
                        secret,
                        Boolean.TRUE.equals(f.discriminator()),
                        f.ui() != null ? f.ui().label() : null,
                        f.ui() != null ? f.ui().helperText() : null,
                        validations,
                        hiddenBy);

                bucketFor(sectionsMap, sectionSeq, f.ui() != null && f.ui().section() != null ? f.ui().section() : "General")
                        .fields.add(new FieldEntry(view, idx));

                // ---- the rendered set: visible, non-virtual, non-action, with a value
                if (!visible) {
                    excluded.add(new ViewModel.ExcludedEntry(inst.instanceKey(), f.key(), "hidden"));
                } else if (virtual) {
                    excluded.add(new ViewModel.ExcludedEntry(inst.instanceKey(), f.key(), "virtual"));
                } else if (secretMalformed) {
                    excluded.add(new ViewModel.ExcludedEntry(inst.instanceKey(), f.key(), "hidden"));
                } else if (raw != null && view.renderedPath() != null) {
                    rendered.add(new ViewModel.RenderedEntry(inst.instanceKey(), view.renderedPath(), raw));
                }
            }

            if (comp.actions() != null) {
                for (PackageModel.PackageAction a : comp.actions()) {
                    List<String> hiddenBy = WhenEvaluator.evaluate(a.when(), values);
                    boolean actionVisible = hiddenBy.isEmpty();
                    ViewModel.ActionView av = new ViewModel.ActionView(
                            a.key(), a.kind(),
                            actionVisible && !versionBlocks,
                            actionVisible,
                            a.inputs() != null ? a.inputs() : List.of(),
                            a.ui() != null ? a.ui().label() : null);
                    bucketFor(sectionsMap, sectionSeq, a.ui() != null && a.ui().section() != null ? a.ui().section() : "General")
                            .actions.add(av);
                    // an action holds no value and appears in no config file
                    excluded.add(new ViewModel.ExcludedEntry(inst.instanceKey(), a.key(), "action"));
                }
            }

            // ---- metrics: declaration-level only. No mark, transform or resolution (DM-D11/D16/D23).
            Map<String, Object> rawGating = observed.restartObservedValues() == null
                    ? null
                    : observed.restartObservedValues().get(inst.instanceKey());
            Map<String, Object> gating = null;
            if (rawGating != null) {
                Map<String, PackageModel.PackageField> byKey = new LinkedHashMap<>();
                for (PackageModel.PackageField f : fields) {
                    byKey.put(f.key(), f);
                }
                gating = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rawGating.entrySet()) {
                    PackageModel.PackageField f = byKey.get(e.getKey());
                    if (f == null) {
                        gating.put(e.getKey(), e.getValue());
                        continue;
                    }
                    DraftValueResolver.NormalizeResult n = DraftValueResolver.normalize(f, e.getValue());
                    if (n.ok()) {
                        gating.put(e.getKey(), n.value());
                    }
                    // normalize failure: leave unset, matching values.set(k, undefined) in TS
                }
            }

            if (comp.metrics() != null) {
                for (PackageModel.PackageMetric m : comp.metrics()) {
                    String state = "collectable";
                    if (m.when() != null) {
                        if (gating == null) {
                            state = "pending-config-change";
                            diagnostics.add(new ViewModel.Diagnostic(
                                    "GATING_STATE_UNKNOWN",
                                    "No restart-observed values for " + inst.instanceKey() + "; " + m.name()
                                            + " is reported pending rather than asserted collectable.",
                                    inst.instanceKey(), null));
                        } else {
                            boolean runningApplies = WhenEvaluator.evaluate(m.when(), gating).isEmpty();
                            boolean draftApplies = WhenEvaluator.evaluate(m.when(), values).isEmpty();
                            state = runningApplies != draftApplies
                                    ? "pending-config-change"
                                    : (runningApplies ? "collectable" : "not-applicable");
                        }
                    }
                    metrics.add(new ViewModel.MetricView(inst.instanceKey(), m.name(), m.metricType(), m.unit(), state));
                }
            }

            // ---- deterministic ordering: section order then title; field ui.order then declaration index
            List<PackageModel.PackageField> fieldsForOrder = fields;
            List<ViewModel.SectionView> sections = sectionsMap.entrySet().stream()
                    .map(e -> {
                        String title = e.getKey();
                        Bucket b = e.getValue();
                        List<ViewModel.FieldView> ordered = b.fields.stream()
                                .sorted(Comparator
                                        .<FieldEntry>comparingLong(fe -> fieldOrder(fieldsForOrder, fe.idx()))
                                        .thenComparingInt(FieldEntry::idx))
                                .map(FieldEntry::view)
                                .toList();
                        List<ViewModel.FieldView> vis = ordered.stream().filter(ViewModel.FieldView::visible).toList();
                        boolean allAdv = !vis.isEmpty() && vis.stream().allMatch(ViewModel.FieldView::advanced);
                        return new ViewModel.SectionView(title, b.order, ordered, b.actions, allAdv);
                    })
                    .sorted(Comparator.comparingInt(ViewModel.SectionView::order)
                            .thenComparing(ViewModel.SectionView::title))
                    .toList();

            components.add(new ViewModel.ComponentView(
                    inst.instanceKey(), comp.type(), comp.role(), sections));
        }

        // FE-D17: byte-stable payload, sorted so the same logical draft hashes identically
        // regardless of key insertion order. Ordinal compare (String.compareTo), never locale.
        rendered.sort(Comparator.comparing(ViewModel.RenderedEntry::instanceKey)
                .thenComparing(ViewModel.RenderedEntry::renderedPath));
        excluded.sort(Comparator.comparing(ViewModel.ExcludedEntry::instanceKey)
                .thenComparing(ViewModel.ExcludedEntry::key)
                .thenComparing(ViewModel.ExcludedEntry::reason));

        ViewModel.StatusView finalStatus = new ViewModel.StatusView(
                status.version(), status.config(), status.replicas(),
                healthRollupOf(anyHealthDeclared, status.replicas()),
                anyHealthDeclared ? "declared" : "not-declared");

        return new ViewModel(
                ViewModel.ENGINE_VERSION, pkg.solution(), packageVersion,
                components, rendered, excluded, metrics, finalStatus, diagnostics);
    }

    // ------------------------------------------------------------------ helpers

    private static String orQuestionMark(String s) {
        return s != null ? s : "?";
    }

    private static Object lastOf(List<Object> list) {
        return (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
    }

    /** A missing declaration scope means every version supported by the package. */
    static boolean inVersion(List<Object> declared, Object selected) {
        return declared == null || containsStrict(declared, selected);
    }

    private static boolean containsStrict(List<Object> haystack, Object needle) {
        if (haystack == null) {
            return false;
        }
        for (Object o : haystack) {
            if (strictEquals(o, needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * JS {@code ===}: see {@link WhenEvaluator#strictEquals}.
     *
     * <p>Package-private (not {@code private}) so {@link DesignerStageValidatorImpl} can reuse
     * the same JS-number-aware comparison for its claimed-vs-rendered drift check, rather than
     * falling back to {@code Objects.equals}'s boxed-type-sensitive comparison there.
     */
    static boolean strictEquals(Object a, Object b) {
        return WhenEvaluator.strictEquals(a, b);
    }

    /** UI-D18: secrecy has one meaning; the schema tolerates two spellings. */
    private static boolean isSecret(PackageModel.PackageField f) {
        return Boolean.TRUE.equals(f.secret()) || "secret".equals(f.type());
    }

    /** UI-D2: equality + AND only, over defaults-applied values. */
    private static List<String> evaluate(Map<String, Object> when, Map<String, Object> values) {
        if (when == null) {
            return List.of();
        }
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Object> e : when.entrySet()) {
            if (!strictEquals(values.get(e.getKey()), e.getValue())) {
                failed.add(e.getKey());
            }
        }
        return failed;
    }

    private static List<Object> optionIds(List<Object> entries, String connectionType) {
        if (entries == null) {
            return null;
        }
        List<Object> ids = new java.util.ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            if (connectionType == null || connectionType.equals(map.get("connectionType"))) {
                ids.add(map.get("id"));
            }
        }
        return ids;
    }

    private static String renderedPathFor(String configPath, String key) {
        return (configPath == null || configPath.isEmpty()) ? key : configPath + "." + key;
    }

    private static List<ViewModel.Validation> bindAndSemanticValidations(
            PackageModel.PackageField f, Object value, String bindError) {
        List<ViewModel.Validation> out = new ArrayList<>();
        if (bindError != null) {
            // UI-D16 bind tier: the MI's binder would fail and the app would not start.
            out.add(new ViewModel.Validation("block", "BIND_TYPE", bindError));
            return out;
        }
        if (value == null) {
            if (Boolean.TRUE.equals(f.required())) {
                out.add(new ViewModel.Validation("block", "REQUIRED", "This field is required."));
            }
            return out;
        }
        if ("enum".equals(f.type()) && f.options() != null && !containsStrict(f.options(), value)) {
            out.add(new ViewModel.Validation("block", "NOT_AN_OPTION",
                    DraftValueResolver.jsonStringify(value) + " is not one of " + DraftValueResolver.jsonStringify(f.options()) + "."));
        }
        // UI-D16 semantic tier: SM's assertion, not the MI's. Warn, never block.
        if (value instanceof Number numVal) {
            double d = numVal.doubleValue();
            if (f.min() != null && d < f.min()) {
                out.add(new ViewModel.Validation("warn", "BELOW_RECOMMENDED",
                        "Below the range Solace recommends (" + fmtNum(f.min()) + "–"
                                + (f.max() != null ? fmtNum(f.max()) : "∞") + ")."));
            }
            if (f.max() != null && d > f.max()) {
                out.add(new ViewModel.Validation("warn", "ABOVE_RECOMMENDED",
                        "Above the range Solace recommends (" + (f.min() != null ? fmtNum(f.min()) : "0")
                                + "–" + fmtNum(f.max()) + ")."));
            }
        }
        if (f.pattern() != null && value instanceof String sVal
                && !Pattern.compile(f.pattern()).matcher(sVal).find()) {
            out.add(new ViewModel.Validation("warn", "PATTERN",
                    "Does not match the pattern Solace recommends (" + f.pattern() + ")."));
        }
        if ("yaml".equals(f.type()) && value instanceof String sVal && YAML_SECRET_SCAN.matcher(sVal).find()) {
            // UI-D23 item 3: cannot be masked, so the operator must be told it is stored in clear.
            out.add(new ViewModel.Validation("warn", "YAML_SECRET_SCAN",
                    "This block looks like it contains a credential. yaml content is stored, diffed and "
                            + "exported in clear."));
        }
        return out;
    }

    private static String fmtNum(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static String healthRollupOf(boolean declared, List<StateModel.ReplicaObservation> replicas) {
        if (!declared) {
            return "NOT_APPLICABLE"; // REQ-009 / I1 — never red
        }
        if (replicas == null || replicas.isEmpty()) {
            return "RED";
        }
        for (StateModel.ReplicaObservation r : replicas) {
            if ("RED".equals(r.health())) {
                return "RED";
            }
        }
        for (StateModel.ReplicaObservation r : replicas) {
            if ("RESTART_EXPECTED".equals(r.health())) {
                return "RESTART_EXPECTED";
            }
        }
        boolean allInactive = true;
        for (StateModel.ReplicaObservation r : replicas) {
            if (!"INACTIVE_REPLICA".equals(r.health())) {
                allInactive = false;
                break;
            }
        }
        return allInactive ? "INACTIVE_REPLICA" : "GREEN";
    }

    private static long fieldOrder(List<PackageModel.PackageField> fields, int idx) {
        PackageModel.PackageField f = idx >= 0 && idx < fields.size() ? fields.get(idx) : null;
        if (f == null || f.ui() == null || f.ui().order() == null) {
            return Long.MAX_VALUE;
        }
        return f.ui().order();
    }

    private static final class Bucket {
        final int order;
        final List<FieldEntry> fields = new ArrayList<>();
        final List<ViewModel.ActionView> actions = new ArrayList<>();

        Bucket(int order) {
            this.order = order;
        }
    }

    private record FieldEntry(ViewModel.FieldView view, int idx) {
    }

    private static Bucket bucketFor(Map<String, Bucket> sectionsMap, int[] sectionSeq, String title) {
        return sectionsMap.computeIfAbsent(title, t -> new Bucket(sectionSeq[0]++));
    }
}
