package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.api.designer.ViewModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code OR-34}: "Stage sends draft values + the rendered set; the server re-projects, refuses
 * a mismatch or an undeclared key, and stores its own result."
 *
 * <p>The server's own re-projection — never the client's claimed {@code rendered} set — is what
 * an {@link DesignerStageValidator.StageOutcome.Accepted} carries forward to be stored ({@code
 * D4} 🔒). The client's claim is used only to detect drift and refuse it; it is never itself
 * persisted.
 *
 * <p>No Spring wiring here on purpose — the engine stays a plain, dependency-free port;
 * {@link DesignerConfiguration} exposes it as a bean, and plan-010 T5.3 wires it behind the stage
 * endpoint.
 */
public final class DesignerStageValidatorImpl implements DesignerStageValidator {

    private final DesignerProjector projector;

    public DesignerStageValidatorImpl(DesignerProjector projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Override
    public StageOutcome validateStage(StageRequest request) {
        ViewModel serverResult = projector.project(
                request.pkg(),
                request.selection(),
                request.draft(),
                request.observed(),
                request.catalog(),
                request.requestedPackageVersion());

        List<StageRefusalReason> reasons = new ArrayList<>();

        // Refuse an undeclared key: EVERY key the draft submits must be declared by the package
        // for a selected instance of a known component type — not just the keys project() chose
        // to diagnose. project()'s own ORPHAN_DRAFT_KEY diagnostic (ported line-for-line from the
        // TS engine, which has the identical scoping) is only ever raised from inside the loop
        // over selected instances of a KNOWN type, so it silently skips two escape routes: a
        // draft key namespaced under an instance that was never selected at all, and a draft key
        // namespaced under a selected instance whose type the package does not declare. Both are
        // "an undeclared key" under OR-34 just as much as a field the package's known type does
        // not list, so this check is computed independently of project()'s diagnostics, directly
        // against the server's own re-projection of which (instanceKey -> declared field keys)
        // is actually known-and-selected.
        Map<String, Set<String>> declaredKeysByInstance = declaredKeysByInstance(serverResult);
        Map<String, Map<String, Object>> draftMap =
                request.draft() == null ? Map.of() : request.draft();
        for (Map.Entry<String, Map<String, Object>> instEntry : draftMap.entrySet()) {
            String instanceKey = instEntry.getKey();
            Map<String, Object> instDraft = instEntry.getValue() == null ? Map.of() : instEntry.getValue();
            Set<String> declared = declaredKeysByInstance.get(instanceKey);
            for (String key : instDraft.keySet()) {
                if (declared == null || !declared.contains(key)) {
                    reasons.add(new StageRefusalReason.UndeclaredDraftKey(instanceKey, key));
                }
            }
        }

        // Refuse a mismatch: the server's own rendered set must agree with what the client
        // claims project() produced — key-for-key, value-for-value. Values compare with the same
        // JS-number-aware equality project() itself uses (DesignerProjectorImpl#strictEquals):
        // a value that normalized to a Java Integer must still match a client claim carrying the
        // numerically identical value as a Long, because the engine's own semantics have exactly
        // one numeric type and Java's boxed equals() does not.
        Map<String, Object> serverByKey = keyedByPath(serverResult.rendered());
        Map<String, Object> claimedByKey = keyedByPath(
                request.claimedRendered() == null ? List.of() : request.claimedRendered());

        for (Map.Entry<String, Object> e : serverByKey.entrySet()) {
            if (!claimedByKey.containsKey(e.getKey())) {
                var parts = splitKey(e.getKey());
                reasons.add(new StageRefusalReason.MissingFromClaim(parts[0], parts[1], e.getValue()));
            } else if (!DesignerProjectorImpl.strictEquals(e.getValue(), claimedByKey.get(e.getKey()))) {
                var parts = splitKey(e.getKey());
                reasons.add(new StageRefusalReason.ValueMismatch(
                        parts[0], parts[1], e.getValue(), claimedByKey.get(e.getKey())));
            }
        }
        for (Map.Entry<String, Object> e : claimedByKey.entrySet()) {
            if (!serverByKey.containsKey(e.getKey())) {
                var parts = splitKey(e.getKey());
                reasons.add(new StageRefusalReason.UnexpectedInClaim(parts[0], parts[1], e.getValue()));
            }
        }

        return reasons.isEmpty()
                ? new StageOutcome.Accepted(serverResult)
                : new StageOutcome.Refused(List.copyOf(reasons), serverResult);
    }

    /**
     * The set of field keys the package actually declares, per selected instance of a known
     * component type — derived from the server's own re-projection rather than recomputed, so
     * this never drifts from what {@code project()} itself resolved (version scoping included).
     * {@code serverResult.components()} holds exactly one {@code ComponentView} per selected
     * instance whose type the package declares (an unknown-type instance is skipped by
     * {@code project()} before it ever reaches this list — see {@code DesignerProjectorImpl}),
     * and each component's sections carry every declared field, visible or not.
     */
    private static Map<String, Set<String>> declaredKeysByInstance(ViewModel serverResult) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (ViewModel.ComponentView c : serverResult.components()) {
            Set<String> keys = new LinkedHashSet<>();
            for (ViewModel.SectionView s : c.sections()) {
                for (ViewModel.FieldView f : s.fields()) {
                    keys.add(f.key());
                }
            }
            out.put(c.instanceKey(), keys);
        }
        return out;
    }

    private static Map<String, Object> keyedByPath(List<ViewModel.RenderedEntry> entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ViewModel.RenderedEntry e : entries) {
            out.put(e.instanceKey() + "\u0000" + e.renderedPath(), e.value());
        }
        return out;
    }

    private static String[] splitKey(String key) {
        int i = key.indexOf('\u0000');
        return new String[] {key.substring(0, i), key.substring(i + 1)};
    }
}
