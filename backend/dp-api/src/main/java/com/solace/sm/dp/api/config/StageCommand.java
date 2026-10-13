package com.solace.sm.dp.api.config;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stage one generation (OR-33/OR-34). {@code baseStateVersion} is the instance {@code stateVersion} the operator's
 * view was built from; a mismatch is a conflict, not a merge.
 *
 * @param componentSelectionJson the selected component instances, as JSON text (an object)
 * @param draftValuesJson        the draft values, as JSON text (an object); a secret field holds a reference only
 * @param rendered               the rendered set the SERVER computed; never the client's claim
 * @param derivedFromGeneration  the generation a rollback, restore or accept copied forward; null for an edit
 * @param layoutJson             OR-96: the designer's canvas layout, as JSON text (an object of instance key to
 *                               {@code {"x": number, "y": number}}), or {@code null} to leave the stored layout
 *                               untouched (a rollback, restore or accept copies content forward, not box positions).
 *                               It is presentation state: it goes to the per-instance presentation record, never into
 *                               a generation, its history or its hash, and a layout carried inside
 *                               {@code componentSelectionJson} is dropped, not stored. {@code "{}"} clears the record.
 * @param loggerSupport          OR-103 / {@code BE-D37}: the package's logger declaration, resolved by the gateway from the
 *                               declared package, or {@code null} when the package declares none. The data plane turns it
 *                               into the SM-owned overlay entries ({@code OverlayKeys}); a client never supplies one, and
 *                               a {@code rendered} set that already carries overlay entries (a copy-forward) cannot also
 *                               declare it.
 * @param activeMetricNames      plan 011 M-Q15: the names of the metrics active for THIS generation's draft, resolved by the
 *                               gateway with the control plane's evaluator. The data plane stores the list with the
 *                               generation, in the same transaction, and never edits it; it evaluates nothing. A
 *                               copy-forward (rollback, restore, a configured log level) does not supply one: the data
 *                               plane copies the source generation's.
 */
public record StageCommand(long baseStateVersion, String packageSolution, String packageVersion,
        String componentSelectionJson, String draftValuesJson, List<RenderedEntryValue> rendered,
        GenerationOrigin origin, Long derivedFromGeneration, UUID actor, String layoutJson,
        LoggerSupport loggerSupport, List<String> activeMetricNames) {

    /**
     * Plan 010's full arity: a stage that carries no active-metric list. Exists so plan 010's tests stay byte-unchanged.
     * ⛔ Like the two below, no {@code src/main} class may call it ({@code MetricsDeclarationConstructorTest}): a
     * production stage through it would record an EMPTY list for the generation, which the collector reads as "nothing is
     * collectable".
     */
    public StageCommand(long baseStateVersion, String packageSolution, String packageVersion,
            String componentSelectionJson, String draftValuesJson, List<RenderedEntryValue> rendered,
            GenerationOrigin origin, Long derivedFromGeneration, UUID actor, String layoutJson,
            LoggerSupport loggerSupport) {
        this(baseStateVersion, packageSolution, packageVersion, componentSelectionJson, draftValuesJson, rendered,
                origin, derivedFromGeneration, actor, layoutJson, loggerSupport, List.of());
    }

    /** A stage whose package declares no logger support (OR-103): no overlay is added. Plan 010's arity, see above. */
    public StageCommand(long baseStateVersion, String packageSolution, String packageVersion,
            String componentSelectionJson, String draftValuesJson, List<RenderedEntryValue> rendered,
            GenerationOrigin origin, Long derivedFromGeneration, UUID actor, String layoutJson) {
        this(baseStateVersion, packageSolution, packageVersion, componentSelectionJson, draftValuesJson, rendered,
                origin, derivedFromGeneration, actor, layoutJson, null, List.of());
    }

    /** A stage that carries no layout: the presentation record is left as it is (OR-96). Plan 010's arity, see above. */
    public StageCommand(long baseStateVersion, String packageSolution, String packageVersion,
            String componentSelectionJson, String draftValuesJson, List<RenderedEntryValue> rendered,
            GenerationOrigin origin, Long derivedFromGeneration, UUID actor) {
        this(baseStateVersion, packageSolution, packageVersion, componentSelectionJson, draftValuesJson, rendered,
                origin, derivedFromGeneration, actor, null, null, List.of());
    }

    public StageCommand {
        Objects.requireNonNull(packageSolution, "package solution");
        Objects.requireNonNull(packageVersion, "package version");
        Objects.requireNonNull(componentSelectionJson, "component selection");
        Objects.requireNonNull(draftValuesJson, "draft values");
        rendered = List.copyOf(Objects.requireNonNull(rendered, "rendered set"));
        activeMetricNames = List.copyOf(Objects.requireNonNull(activeMetricNames, "active metric names"));
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(actor, "actor");
        if (derivedFromGeneration != null && derivedFromGeneration < 1) {
            throw new IllegalArgumentException("a derived-from generation is a positive number");
        }
    }

    /** Names the sizes and the origin, never the draft values, which are configuration. */
    @Override
    public String toString() {
        return "StageCommand[base=" + baseStateVersion + ", " + packageSolution + "@" + packageVersion + ", "
                + origin + ", " + rendered.size() + " rendered entries]";
    }
}
