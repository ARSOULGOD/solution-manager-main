package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (h) and (j): every named CHECK in design §3.1, refused by a row that violates IT and
 * nothing else.
 *
 * <p><b>One probe, one violation</b> — the harness rule the prototype learned the hard way, when an
 * {@code op_known} probe was refused by {@code apply_has_generation} instead. Every probe here starts
 * from {@link #legalRequest()}, a row that satisfies all eleven CHECKs simultaneously, and changes
 * exactly the field under test; the javadoc on {@link #legalRequest()} records which value answers
 * which constraint, so a future edit can see what it is about to break.
 *
 * <p>Hand-written SQL through the runtime template, deliberately: the repositories and the model
 * records refuse most of these shapes in Java before they can reach PostgreSQL, and a test that could
 * not express an illegal row could not prove the database refuses one.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaConstraintsIT {

    private static final String INSERT_REQUEST = "INSERT INTO sm_data.apply_request"
            + " (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
            + "  outage_confirmed_by, requested_by, requested_at, state, terminal_at, resolved_by,"
            + "  resolution_outcome, resolution_observed)"
            + " VALUES (:apply_id, :tenant_id, :instance_id, :placement_id, :operation, :generation,"
            + " :strategy, :outage_confirmed_by, :requested_by, :requested_at, :state, :terminal_at,"
            + " :resolved_by, :resolution_outcome, CAST(:resolution_observed AS jsonb))";
    // resolution_note is deliberately not in the list: the runtime role holds no INSERT or UPDATE
    // privilege on it (I6 — v1 writes no free text), so naming it would turn every CHECK probe
    // here into a 42501. Its own refusal is PrivilegeTopologyIT's.

    /**
     * Object, {@code stagedGeneration} EQUAL to {@link #legalRequest()}'s own {@code generation}
     * (1L), NON-EMPTY {@code replicas} array — everything the apply branch of the CHECK demands
     * ({@code BE-D17}, §3.4). Before {@code BE-D17} this value only had to be SOME number; now it
     * must equal the row's own generation, which is why it is 1, not an arbitrary 12.
     *
     * <p>{@code BE-D18} (R6): the element now also carries {@code observedHash} — present, as JSON
     * {@code null} — because the well-formedness predicate makes the KEY mandatory (its value stays
     * nullable). Before this amendment the element had only {@code ref}/{@code restarted}; that
     * shape is exactly the one {@code BE-D18} closes, so this fixture would now be refused if left
     * unchanged. A fixture-only change: nothing this constant is used to prove shifts meaning.
     */
    private static final String GOOD_EVIDENCE = "{\"stagedGeneration\": 1, \"replicas\": "
            + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}";

    /** The restart branch's own positive shape: {@code restartsObserved}, no {@code stagedGeneration}
     * key at all ({@code BE-D17}, §3.4). */
    private static final String GOOD_RESTART_EVIDENCE =
            "{\"restartsObserved\": [{\"ref\": \"r1\", \"restarted\": true}]}";

    private static NamedParameterJdbcTemplate runtime;
    private static UUID tenant;
    private static UUID instance;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = ApplyEngineHarness.jdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        ApplyEngineHarness.scopes().ensureScopeRow(new TenantId(tenant), new InstanceId(instance));
    }

    // -------------------------------------------------------------------------------------------
    // (h): the four the obligation names, plus the rest of apply_request's CHECK set.
    // -------------------------------------------------------------------------------------------

    @Test
    void aStateOutsideTheEnumIsRefusedByStateKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "state_known",
                () -> insert(legalRequest()
                        .addValue("state", "ALMOST_DONE")
                        // …terminal_has_timestamp is an equality over the KNOWN open states, so an
                        // unknown state counts as terminal and must carry a stamp, or two CHECKs
                        // would fire and the probe would prove neither.
                        .addValue("terminal_at", OffsetDateTime.now())));
    }

    @Test
    void anOperationOutsideTheEnumIsRefusedByOpKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "op_known",
                () -> insert(legalRequest()
                        .addValue("operation", "dance")
                        .addValue("generation", null)
                        .addValue("strategy", null)));
    }

    /**
     * {@code BE-D16} finding: this probe used {@code STAGED} as its illegal exemplar state before
     * {@code staged_requires_manual} existed. Measured after the CHECK landed: a {@code stop} row
     * with {@code state = 'STAGED'} now violates {@code staged_requires_manual} too (strategy is
     * forced NULL for {@code stop} by {@code lifecycle_has_strategy}, so {@code coalesce(strategy =
     * 'manual', false)} is always false there) — PostgreSQL reports {@code staged_requires_manual},
     * not {@code state_fits_operation}, for the identical row. {@code APPLIED} is excluded by the
     * same {@code stop} allow-list and carries no such second violation, so it isolates
     * {@code state_fits_operation} the way {@code STAGED} used to before this amendment.
     */
    @Test
    void aStopThatClaimsToBeAppliedIsRefusedByStateFitsOperation() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "state_fits_operation",
                () -> insert(legalRequest()
                        .addValue("operation", "stop")
                        .addValue("generation", null)
                        .addValue("strategy", null)
                        .addValue("state", "APPLIED")
                        .addValue("terminal_at", OffsetDateTime.now())));
    }

    @Test
    void anApplyWithoutAGenerationIsRefusedByApplyHasGeneration() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_has_generation",
                () -> insert(legalRequest().addValue("generation", null)));
    }

    @Test
    void aRestartWithoutAStrategyIsRefusedByLifecycleHasStrategy() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "lifecycle_has_strategy",
                () -> insert(legalRequest()
                        .addValue("operation", "restart")
                        .addValue("generation", null)
                        .addValue("strategy", null)));
    }

    @Test
    void aManualRestartIsRefusedByRestartNotManual() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "restart_not_manual",
                () -> insert(legalRequest()
                        .addValue("operation", "restart")
                        .addValue("generation", null)
                        .addValue("strategy", "manual")));
    }

    @Test
    void aFullOutageRestartWithoutAConfirmationIsRefusedByFullOutageConfirmed() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "full_outage_confirmed",
                () -> insert(legalRequest().addValue("strategy", "restart-all")));

        insert(legalRequest()
                .addValue("strategy", "restart-all")
                .addValue("outage_confirmed_by", "duty-manager-3"));
    }

    @Test
    void aStrategyOutsideTheLadderIsRefusedByStrategyKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "strategy_known",
                () -> insert(legalRequest().addValue("strategy", "yolo")));
    }

    @Test
    void aTerminalStateWithoutATimestampIsRefusedByTerminalHasTimestamp() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "terminal_has_timestamp",
                () -> insert(legalRequest().addValue("state", "APPLIED")));

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "terminal_has_timestamp",
                () -> insert(legalRequest().addValue("terminal_at", OffsetDateTime.now())),
                "…and the other direction: REQUESTED with a terminal stamp");
    }

    @Test
    void aNonPositiveGenerationIsRefusedByGenerationPositive() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "generation_positive",
                () -> insert(legalRequest().addValue("generation", 0L)));
    }

    // -------------------------------------------------------------------------------------------
    // BE-D16: staged_requires_manual — state = 'STAGED' implies strategy = 'manual', with no
    // exception. Obligation: refuse each of the four non-manual ladder strategies by name, and
    // accept a manual one (the positive control) — the direct counterpart of BE-D17's four classes.
    // -------------------------------------------------------------------------------------------

    @Test
    void aNonManualStagedRowIsRefusedByStagedRequiresManualForEveryLadderStrategy() {
        for (String strategy : List.of("rolling-health-gated", "rolling-no-validation",
                "restart-all", "scheduled")) {
            // restart-all supplies its own outage confirmation, or full_outage_confirmed would fire
            // too and the probe would no longer isolate staged_requires_manual.
            String outageConfirmedBy = "restart-all".equals(strategy) ? "duty-manager-3" : null;
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "staged_requires_manual",
                    () -> insert(legalRequest()
                            .addValue("strategy", strategy)
                            .addValue("state", "STAGED")
                            .addValue("terminal_at", OffsetDateTime.now())
                            .addValue("outage_confirmed_by", outageConfirmedBy)),
                    strategy + " must not be able to hold STAGED");
        }
    }

    @Test
    void aManualStagedRowIsAcceptedThePositiveControlForStagedRequiresManual() {
        insert(legalRequest()
                .addValue("strategy", "manual")
                .addValue("state", "STAGED")
                .addValue("terminal_at", OffsetDateTime.now()));
    }

    /**
     * Coverage restored (R3 carry-in of the {@code BE-D17} repair wave). R2 retargeted the
     * {@code state_fits_operation} probe above from {@code STAGED} to {@code APPLIED} because a
     * {@code stop} + {@code STAGED} row now ALSO violates {@code staged_requires_manual} — but that
     * retargeting left nothing in the suite exercising a {@code stop} + {@code STAGED} row at all.
     * Measured: such a row is refused, but by name it is {@code staged_requires_manual}, not
     * {@code state_fits_operation} — {@code stop} forces {@code strategy} NULL
     * ({@code lifecycle_has_strategy}), so {@code coalesce(strategy = 'manual', false)} is always
     * false there, and it is {@code staged_requires_manual}'s own coalesced text that refuses it
     * directly (design §3.2's R3 correction: which of the two simultaneously-violated constraints
     * PostgreSQL names is the two constraints' own lexical order, {@code staged_requires_manual}
     * before {@code state_fits_operation}).
     */
    @Test
    void aStopThatClaimsToBeStagedIsRefusedByStagedRequiresManual() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "staged_requires_manual",
                () -> insert(legalRequest()
                        .addValue("operation", "stop")
                        .addValue("generation", null)
                        .addValue("strategy", null)
                        .addValue("state", "STAGED")
                        .addValue("terminal_at", OffsetDateTime.now())));
    }

    // -------------------------------------------------------------------------------------------
    // (j): resolution_is_evidence, clause by clause, and resolution_only_when_resolved.
    // -------------------------------------------------------------------------------------------

    @Test
    void everyWorthlessResolutionIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("no resolver named", resolved().addValue("resolved_by", null));
        assertResolutionRefused("no outcome", resolved().addValue("resolution_outcome", null));
        assertResolutionRefused("no evidence at all",
                resolved().addValue("resolution_observed", null));
        assertResolutionRefused("an empty object",
                resolved().addValue("resolution_observed", "{}"));
        assertResolutionRefused("JSON null (typeof 'null', not 'object')",
                resolved().addValue("resolution_observed", "null"));
        assertResolutionRefused("present keys, worthless values",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": null, \"replicas\": []}"));
        assertResolutionRefused("a STRING stagedGeneration",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": \"12\", \"replicas\": "
                                + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}"));
        assertResolutionRefused("an EMPTY replicas array",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 12, \"replicas\": []}"));
        assertResolutionRefused("replicas that is not an array",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 12, \"replicas\": \"r1\"}"));
    }

    @Test
    void aFullyEvidencedResolutionInsertsThePositiveControl() {
        // Scoped to this probe's OWN apply_id, not the class's shared tenant: the resolution_known
        // probe below inserts a second successful INDETERMINATE_RESOLVED row (its own positive
        // control) into the same static tenant, and a tenant-wide count over a shared fixture is
        // order-dependent on JUnit's method ordering. Reproduced at 2 of 6 random-order seeds before
        // this fix (fix round: flagship CHECK-coverage class finding).
        MapSqlParameterSource params = resolved();
        insert(params);
        assertEquals(1, runtime.queryForObject(
                "SELECT count(*)::int FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = 'INDETERMINATE_RESOLVED'",
                new MapSqlParameterSource("t", tenant)
                        .addValue("a", params.getValue("apply_id")), Integer.class),
                "the CHECK is evidence-SHAPED, not impossible to satisfy");
    }

    @Test
    void aResolutionFieldOnAnyOtherStateIsRefusedByResolutionOnlyWhenResolved() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_only_when_resolved",
                () -> insert(legalRequest()
                        .addValue("state", "RUNNING")
                        .addValue("resolved_by", "a resolver nobody has asked for yet")));
    }

    /**
     * {@code resolution_known} — an (h)-style enum CHECK on {@code resolution_outcome}, placed here
     * beside the rest of the resolution family rather than up in the (h) block, because every other
     * probe that can reach it is a {@link #resolved()} row: {@code resolution_is_evidence} only
     * requires {@code resolution_outcome IS NOT NULL} (true for ANY non-null string, including an
     * out-of-enum one) and {@code resolution_only_when_resolved} only requires the state to be
     * {@code INDETERMINATE_RESOLVED}, so a fully-evidenced row with an out-of-enum outcome trips
     * exactly {@code resolution_known} and nothing else.
     *
     * <p>Found missing (fix round 1): this CHECK was pinned by nothing in the suite —
     * {@code ResolutionOutcome} mirrors it in Java ({@code APPLIED}/{@code NOT_APPLIED}) with no test
     * tying the two, the same drift class {@code StateFitsOperationMirrorTest} closes for
     * {@code state_fits_operation}. A future widening of the CHECK without a matching enum value would
     * pass a database write and then throw in {@code ResolutionOutcome.fromSql} on read.
     */
    @Test
    void aResolutionOutcomeOutsideTheEnumIsRefusedByResolutionKnown() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_known",
                () -> insert(resolved().addValue("resolution_outcome", "maybe")));

        // Positive control: the OTHER legal value (aFullyEvidencedResolutionInsertsThePositiveControl
        // already proves 'applied') — the CHECK is a two-value allow-list, not a value ban.
        insert(resolved().addValue("resolution_outcome", "not_applied"));
    }

    // -------------------------------------------------------------------------------------------
    // BE-D17: resolution_is_evidence's operation-specific CASE (design §3.4). Obligation: raw SQL
    // must refuse each of the four rejection classes by name, alongside a positive control for the
    // restart shape (the apply shape's positive control is
    // aFullyEvidencedResolutionInsertsThePositiveControl above, now generation-bound).
    // -------------------------------------------------------------------------------------------

    @Test
    void aFullyEvidencedRestartResolutionInsertsThePositiveControl() {
        MapSqlParameterSource params = resolvedRestart();
        insert(params);
        assertEquals(1, runtime.queryForObject(
                "SELECT count(*)::int FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = 'INDETERMINATE_RESOLVED'",
                new MapSqlParameterSource("t", tenant)
                        .addValue("a", params.getValue("apply_id")), Integer.class),
                "the restart branch is evidence-SHAPED, not impossible to satisfy either");
    }

    /**
     * Rejection class (ii): {@code stagedGeneration} present, numeric, but ≠ {@code generation}.
     *
     * <p>{@code BE-D18} fix round 1 (R6): an external review measured that landing {@code BE-D18}
     * left this leg's element ({@code [{"ref": "r1"}]}) missing {@code restarted}/{@code
     * observedHash} — which the new well-formedness predicate now refuses **on its own**, so the
     * generation-equality clause this leg exists to pin (the whole reason {@code BE-D17} exists)
     * could be deleted from {@code V002} today and this probe would still be green. Element-completed
     * here; the per-clause mutation re-run below confirms the pin is restored.
     */
    @Test
    void anApplyResolutionWithAMismatchedStagedGenerationIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("stagedGeneration 2 against generation 1",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 2, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}"));
    }

    /**
     * The numeric-equality nuance, measured rather than assumed (design §3.4): the apply clause
     * compares {@code jsonb} values, so {@code 1.0} — textually different from the row's own
     * {@code generation = 1}, numerically identical — is ACCEPTED, and {@code 1.5} is refused. A
     * textual-equality implementation would get the first case wrong.
     *
     * <p>{@code BE-D18} (R6): the positive leg's element gained {@code "restarted": true,
     * "observedHash": null} — a fixture-only change, made for the same reason as {@link
     * #GOOD_EVIDENCE}'s.
     *
     * <p><b>Corrected, {@code BE-D18} fix round 1 (R6):</b> the previous text here claimed the
     * {@code 1.5} leg "stays element-incomplete deliberately: it is refused by the
     * generation-mismatch clause regardless, so its shape is irrelevant to what it proves." That
     * inverted the question. An external review measured that, element-incomplete, this leg is
     * refused by the new well-formedness predicate REGARDLESS of the generation clause — so it had
     * stopped pinning the equality clause at all, the same failure mode as
     * {@link #anApplyResolutionWithAMismatchedStagedGenerationIsRefusedByResolutionIsEvidence}'s.
     * Element-completed here for the same reason.
     */
    @Test
    void anApplyResolutionsStagedGenerationIsCheckedNumericallyNotTextually() {
        insert(resolved().addValue("resolution_observed",
                "{\"stagedGeneration\": 1.0, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}"));

        assertResolutionRefused("a non-integral stagedGeneration that is not numerically equal",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1.5, \"replicas\": "
                                + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}"));
    }

    /**
     * Important 1 (R3 fix round 1): the legacy apply-side observation legs in
     * {@link #everyWorthlessResolutionIsRefusedByResolutionIsEvidence} all carry
     * {@code stagedGeneration: 12} against {@link #legalRequest()}'s {@code generation = 1} — post-
     * {@code BE-D17} every one of them is ALREADY refused by the equality clause, so the two array
     * clauses (non-empty, typeof array) were pinned by nothing there: measured, deleting both from
     * {@code V002} left all eleven apply-side legs green (r3-report.md carries the per-clause-mutation
     * numbers). These two legs use a MATCHING generation, so the equality clause passes and the array
     * clauses are the sole possible refusers.
     *
     * <p><b>{@code BE-D18} fix round 1 (R6):</b> the second leg's value changed from the STRING
     * {@code "r1"} to a bare, otherwise well-formed OBJECT. A string, unlike an object, is caught a
     * second way post-{@code BE-D18}: {@code $.replicas[*]} auto-wraps any non-array scalar into a
     * single-item pseudo-array under {@code lax} mode, so a lone string is presented to the new
     * element-type-check predicate as an item whose {@code .type()} is {@code "string"} — refused a
     * second, independent way, unpinning {@code jsonb_typeof(replicas) = 'array'} exactly as the
     * equality clause above was unpinned. A bare well-formed OBJECT auto-wraps to a single item whose
     * {@code .type()} IS {@code "object"} with the right keys and values, so none of the six new
     * predicates can touch it — only {@code jsonb_typeof} (which reads the raw value, never through
     * {@code [*]}) sees it is not an array. Measured, per-clause mutation re-run below.
     */
    @Test
    void anEmptyOrNonArrayReplicasWithAMatchingStagedGenerationIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("an EMPTY replicas array, stagedGeneration matching generation",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": []}"));
        assertResolutionRefused("replicas that is not an array (a bare well-formed object), "
                        + "stagedGeneration matching generation",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}}"));
    }

    /**
     * Minor 4 (R3 fix round 1): the suite's only pre-existing string {@code stagedGeneration} leg was
     * {@code "12"}, which casts cleanly to {@code numeric} — a regression to
     * {@code (resolution_observed ->> 'stagedGeneration')::numeric = generation} would refuse it too
     * (a legal-but-unequal numeric value), so that leg cannot distinguish a safe jsonb-to-jsonb
     * comparison from a throwing cast. A non-numeric string forces the distinction: the real CHECK
     * refuses it cleanly (a jsonb string never equals a jsonb number), while a cast-based mutant
     * throws {@code 22P02} instead of refusing {@code 23514} for the identical row.
     *
     * <p>{@code BE-D18} fix round 1 (R6): element-completed, same reason and same restored pin as
     * {@link #anApplyResolutionWithAMismatchedStagedGenerationIsRefusedByResolutionIsEvidence}'s.
     */
    @Test
    void aNonNumericStringStagedGenerationIsRefusedByResolutionIsEvidenceNotByAThrowingCast() {
        assertResolutionRefused("a non-numeric string stagedGeneration",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": \"r1\", "
                        + "\"replicas\": [{\"ref\": \"r1\", \"restarted\": true, "
                        + "\"observedHash\": null}]}"));
    }

    /**
     * The remaining half of the throw-freedom obligation (design §3.4), closed by a text-level
     * assertion rather than by more permutation coverage (R4 of the repair wave; see
     * {@code decisions.md}'s corrected changelog entry for R3 fix round 1's Minor 4).
     * {@code SketchMirrorTest} locks the design's SQL block and {@code V002} to each other as TEXT,
     * so reverting {@code resolution_is_evidence}'s two array clauses back to
     * {@code jsonb_array_length(...) > 0} in BOTH files passes that mirror, and passes every
     * behavioural test in this class too — the reverted CHECK still refuses every malformed row this
     * suite tries, just by sometimes throwing {@code 22023} instead of refusing {@code 23514}
     * deterministically, exactly the undefined-AND-order flakiness design §3.4 spent two review
     * rounds eliminating. This reads the constraint's OWN INSTALLED DEFINITION back from the
     * catalog — not source text on either side — so it fails the moment the shipped CHECK calls the
     * function, independent of which file changed first or whether the two still agree with each
     * other.
     */
    @Test
    void resolutionIsEvidenceNeverCallsJsonbArrayLength() {
        String definition = runtime.getJdbcTemplate().queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'resolution_is_evidence'",
                String.class);
        assertFalse(definition.contains("jsonb_array_length"),
                "resolution_is_evidence must never call jsonb_array_length: it throws 22023 on a "
                        + "non-array operand under some AND-orderings (design §3.4, measured) — exactly "
                        + "the erroring-operand shape the '[]'::jsonb comparison replaced it with. "
                        + "Installed definition was: " + definition);
    }

    /** Rejection class (i), direction 1: apply-shaped evidence resolving a {@code restart} request. */
    @Test
    void anApplyShapedEvidenceResolvingARestartRequestIsRefusedByResolutionIsEvidence() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_is_evidence",
                () -> insert(resolvedRestart().addValue("resolution_observed", GOOD_EVIDENCE)),
                "GOOD_EVIDENCE carries stagedGeneration, which the restart branch refuses outright");
    }

    /** Rejection class (i), direction 2: restart-shaped evidence resolving an {@code apply} request. */
    @Test
    void aRestartShapedEvidenceResolvingAnApplyRequestIsRefusedByResolutionIsEvidence() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_is_evidence",
                () -> insert(resolved().addValue("resolution_observed", GOOD_RESTART_EVIDENCE)),
                "GOOD_RESTART_EVIDENCE has no stagedGeneration key, which the apply branch requires");
    }

    /**
     * Rejection class (iv): a {@code restart} resolution whose evidence carries a
     * {@code stagedGeneration} key AT ALL, refused by the key's mere presence rather than by its
     * value — a value that would even be correct, were this an apply resolution.
     *
     * <p><b>N2 (R7 of the repair wave):</b> the element was {@code [{"ref": "r1"}]} — missing
     * {@code restarted} — which the restart branch's own element well-formedness predicate refuses
     * on its own: the identical document with {@code stagedGeneration} removed is refused too, so
     * this leg proved nothing about "the key's mere presence" as claimed above. Element-completed to
     * isolate the top-level key-set predicate (and the now-subsumed {@code jsonb_exists} clause it
     * shares the refusal with) instead. Changes no pin — that clause is permanently unpinnable, per
     * the R6 fix round above — but a legacy, element-incomplete fixture sitting unchanged beside a
     * new predicate whose job is to complete exactly that shape is the tell {@code docs/design/
     * common/lessons.md} L20 names, regardless of whether a pin moves.
     */
    @Test
    void aRestartResolutionWithAnInventedStagedGenerationIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("stagedGeneration present on a restart resolution, any value",
                resolvedRestart().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"restartsObserved\": "
                                + "[{\"ref\": \"r1\", \"restarted\": true}]}"));
    }

    /**
     * Rejection class (iii), the restart shape's own: missing, empty, or non-array observations.
     *
     * <p>{@code BE-D18} fix round 1 (R6): the third leg's value changed from the STRING {@code "r1"}
     * to a bare, otherwise well-formed OBJECT — the exact same reason and restored pin as
     * {@link #anEmptyOrNonArrayReplicasWithAMatchingStagedGenerationIsRefusedByResolutionIsEvidence}'s
     * apply-side mirror.
     *
     * <p><b>N3 (R7 of the repair wave):</b> that retarget left the restart branch with no
     * STRING-shaped {@code restartsObserved} leg at all, while the apply mirror kept its own (the
     * STRING {@code "r1"} leg in {@link #everyWorthlessResolutionIsRefusedByResolutionIsEvidence}).
     * Behaviour was never in doubt — a bare string is refused by
     * {@code jsonb_typeof(restartsObserved) = 'array'} and, independently, by the element-type-check
     * predicate via {@code lax} mode's {@code [*]} auto-wrap — but nothing asserted it; restored
     * below.
     */
    @Test
    void aRestartResolutionWithMissingOrEmptyObservationsIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("no restartsObserved key at all",
                resolvedRestart().addValue("resolution_observed", "{}"));
        assertResolutionRefused("an EMPTY restartsObserved array",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": []}"));
        assertResolutionRefused("restartsObserved that is not an array (a bare STRING)",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": \"r1\"}"));
        assertResolutionRefused("restartsObserved that is not an array (a bare well-formed object)",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": "
                        + "{\"ref\": \"r1\", \"restarted\": true}}"));
    }

    // -------------------------------------------------------------------------------------------
    // BE-D18 (design §3.4, R6 of the R18 repair wave): resolution_is_evidence's per-element and
    // key-exactness predicates — six for 'apply', four for 'restart'. Every leg below is measured
    // (developer, PG 14.24, standalone table carrying only this CHECK) against a per-clause
    // mutation table before landing — see r6-report.md for which predicate each leg pins.
    // -------------------------------------------------------------------------------------------

    /**
     * Element well-formedness, the apply branch (predicate 2 of 6): an object missing a required
     * component, or the element itself not an object at all (the two non-array malformations —
     * the array-valued escapes are their own class, below).
     */
    @Test
    void anApplyReplicaElementMissingARequiredComponentIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("an empty object element",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": [{}]}"));
        assertResolutionRefused("a JSON null element",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": [null]}"));
        assertResolutionRefused("a missing ref",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"restarted\": true, \"observedHash\": null}]}"));
        assertResolutionRefused("a blank ref",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"   \", \"restarted\": true, \"observedHash\": null}]}"));
        assertResolutionRefused("a non-string ref",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": 7, \"restarted\": true, \"observedHash\": null}]}"));
        assertResolutionRefused("a missing restarted",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"observedHash\": null}]}"));
        assertResolutionRefused("a non-boolean restarted",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": \"yes\", \"observedHash\": null}]}"));
    }

    /**
     * The key-required half of element well-formedness (design §3.4's "seven, not six" hostile
     * shape): {@code observedHash} entirely absent is refused, distinctly from a present {@code
     * null} — the positive control proving the distinction is
     * {@link #aFullyEvidencedResolutionInsertsThePositiveControl}, whose {@link #GOOD_EVIDENCE} now
     * carries a present {@code null}.
     */
    @Test
    void anApplyReplicaElementWithObservedHashEntirelyAbsentIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("observedHash key absent entirely (not merely null-valued)",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true}]}"));
    }

    /** Element key-set (predicate 3 of 6): a key outside {ref, restarted, observedHash}. */
    @Test
    void anApplyReplicaElementWithAnUnexpectedKeyIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("an extra element key",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null, "
                        + "\"extra\": 1}]}"));
    }

    /** Top-level key-set (predicate 6 of 6): a key outside {stagedGeneration, replicas}. */
    @Test
    void anApplyResolutionWithAnUnexpectedTopLevelKeyIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("an extra top-level key",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}], "
                        + "\"extra\": 1}"));
    }

    /**
     * Hash format (predicate 5 of 6), the four shapes the format clause alone must refuse — an
     * absent key is element well-formedness's own, above, and unreachable here by construction
     * (an absent key is refused before the format clause is ever asked about a value).
     */
    @Test
    void anApplyReplicaElementWithAMalformedHashValueIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("a hash with no algorithm-colon-hex shape at all",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": \"nope\"}]}"));
        assertResolutionRefused("a non-string (numeric) observedHash",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": 7}]}"));
        assertResolutionRefused("a 15-hex hash, one short of the {16,128} floor",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, "
                        + "\"observedHash\": \"sha256:012345678901234\"}]}"));
        assertResolutionRefused("a 129-hex hash, one past the {16,128} cap",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": \"sha256:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0\"}]}"));
        assertResolutionRefused("an uppercase hash — the grammar is lowercase-only",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, "
                        + "\"observedHash\": \"SHA256:0123456789ABCDEF\"}]}"));
    }

    /**
     * The {@code lax}-mode array-unwrap escape (R5 fix round 1 of the design, predicates 1 and 4 of
     * 6): an array-VALUED {@code replicas} element, or an array-valued {@code observedHash}, both
     * of which a plain filter alone cannot see as malformed because {@code lax} mode auto-unwraps
     * the array and re-applies the filter to ITS elements instead.
     */
    @Test
    void anApplyReplicaElementThatIsItselfAnArrayIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("a single empty-array element — the pure zero-observation write",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": [[]]}"));
        assertResolutionRefused("two empty-array elements",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": [[],[]]}"));
        assertResolutionRefused("an array element wrapping an otherwise well-formed object",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]]}"));
        assertResolutionRefused("a well-formed element beside an array element",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}, []]}"));
    }

    @Test
    void anApplyReplicaElementWithAnArrayValuedObservedHashIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("observedHash as an empty array",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": []}]}"));
        assertResolutionRefused("observedHash as an array holding a valid-shaped hash",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, "
                        + "\"observedHash\": [\"sha256:0123456789abcdef\"]}]}"));
        assertResolutionRefused("observedHash as an array holding a JSON null",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": [null]}]}"));
    }

    /**
     * The restart branch's mirror of well-formedness — the same five malformations, minus the
     * {@code observedHash} axis, which does not exist for a restart element at all (its absence is
     * proven by {@link #aRestartObservationElementCarryingAnObservedHashKeyIsRefusedByResolutionIsEvidence}
     * below, the element key-set clause).
     */
    @Test
    void aRestartObservationElementMissingARequiredComponentIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("an empty object element",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{}]}"));
        assertResolutionRefused("a missing ref",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"restarted\": true}]}"));
        assertResolutionRefused("a blank ref",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"ref\": \"  \", \"restarted\": true}]}"));
        assertResolutionRefused("a non-string ref",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"ref\": 7, \"restarted\": true}]}"));
        assertResolutionRefused("a missing restarted",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"ref\": \"r1\"}]}"));
        assertResolutionRefused("a non-boolean restarted",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"ref\": \"r1\", \"restarted\": \"yes\"}]}"));
    }

    /**
     * Restart element key-set: the clause that makes "a restart observation has no {@code
     * observedHash}" a DB-enforced fact, not merely a Java-typed one (design §3.4).
     */
    @Test
    void aRestartObservationElementCarryingAnObservedHashKeyIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("observedHash present on a restart element, even as null",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}"));
    }

    /** Restart top-level key-set: a key outside {restartsObserved}. */
    @Test
    void aRestartResolutionWithAnUnexpectedTopLevelKeyIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("an extra top-level key",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true}], \"extra\": 1}"));
    }

    /** The restart branch's own array-unwrap escape — the same hazard, no observedHash axis. */
    @Test
    void aRestartObservationElementThatIsItselfAnArrayIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("a single empty-array element",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [[]]}"));
        assertResolutionRefused("an array element wrapping an otherwise well-formed object",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": "
                        + "[[{\"ref\": \"r1\", \"restarted\": true}]]}"));
        assertResolutionRefused("a well-formed element beside an array element",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": "
                        + "[{\"ref\": \"r1\", \"restarted\": true}, []]}"));
    }

    /**
     * {@code {"restartsObserved": [null]}} — the container-only CHECK's own accepted escape that
     * motivated {@code BE-D18} (design §3.4's opening measurement), named as its own leg rather than
     * folded into the well-formedness test above.
     */
    @Test
    void aRestartObservationOfPureNullsIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("the exact escape BE-D18 exists to close: a lone null observation",
                resolvedRestart().addValue("resolution_observed", "{\"restartsObserved\": [null]}"));
    }

    /**
     * Positive controls that must keep passing: a present-null {@code observedHash} (also proven by
     * {@link #GOOD_EVIDENCE}, restated here as its own leg), a valid 16-hex and a valid 128-hex
     * hash, and several replicas. {@code BE-D19} adds three more: a Kubernetes-shaped identifier
     * past the withdrawn {@code VALUE}-based draft's 128-character cap (200 chars — RFC 1123
     * subdomain names run to 253), a systemd-escaped unit-name fragment carrying a literal
     * backslash, and {@code REPLICA_REF}'s own {1,255} length boundary's ACCEPTED edge (255 chars)
     * — the two disqualifying cases this amendment exists to admit, plus the bound itself. The
     * REFUSED edge (256) has its own test, {@link
     * #aRefLongerThan255CharactersIsRefusedByResolutionIsEvidence}.
     *
     * <p><b>Two legs moved out, {@code BE-D19}:</b> a {@code ref} carrying quotes, a backslash,
     * {@code =}, a space and a non-ASCII character (café), and a {@code ref} carrying an embedded
     * newline — both ACCEPTED under the withdrawn {@code isBlank()}/{@code like_regex "\\S"} gate
     * ("at least one non-whitespace character exists", not "every character is non-whitespace") —
     * are now REFUSED under {@code REPLICA_REF}'s closed character class (space, quote, {@code =},
     * non-ASCII and newline are all outside it). Their proof moved to {@link
     * #aRefContainingCharactersOutsideReplicaRefIsRefusedByResolutionIsEvidence}, named there
     * rather than left as a stale accepted fixture beside the predicate that now refuses it
     * (the {@code L20} caution this repair wave keeps naming).
     */
    @Test
    void everyLegitimateApplyReplicaShapeIsAcceptedByResolutionIsEvidence() {
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null}]}"));
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"r1\", \"restarted\": true, "
                + "\"observedHash\": \"sha256:0123456789abcdef\"}]}"));
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": \"sha256:"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}]}"));
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"r1\", \"restarted\": true, \"observedHash\": null},"
                + " {\"ref\": \"r2\", \"restarted\": false, "
                + "\"observedHash\": \"sha256:0123456789abcdef\"}]}"));

        // BE-D19: the two disqualifying cases, now proven accepted — a Kubernetes-shaped
        // identifier past 128 characters, and a systemd-escaped fragment carrying one literal
        // backslash (measured against moby/moby and systemd.unit(5), design §3.4).
        String kubernetesShaped200 = "a".repeat(200);
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"" + kubernetesShaped200 + "\", \"restarted\": true, "
                + "\"observedHash\": null}]}"));
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"foo\\\\x2dbar.mount\", \"restarted\": true, "
                + "\"observedHash\": null}]}"));

        // The {1,255} length boundary's ACCEPTED edge.
        String atTheLengthBoundary255 = "a".repeat(255);
        insert(resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                + "[{\"ref\": \"" + atTheLengthBoundary255 + "\", \"restarted\": true, "
                + "\"observedHash\": null}]}"));
    }

    // -------------------------------------------------------------------------------------------
    // BE-D19 (design §3.4's amendment to resolution_is_evidence's own well-formedness predicate):
    // ValueGrammar.REPLICA_REF replaces the withdrawn isBlank()/like_regex "\\S" gate on every
    // "ref" — a closed character class, not merely non-blank. Obligations below, by name.
    // -------------------------------------------------------------------------------------------

    /**
     * Obligation 4: the two literals that flip from accepted to refused, moved here from {@link
     * #everyLegitimateApplyReplicaShapeIsAcceptedByResolutionIsEvidence} rather than left beside it
     * as a stale accepted fixture ({@code L20}) — a space, two double-quotes, {@code =} and the
     * accented Latin {@code é} in the first; the embedded newline (U+000A) in the second. Obligation
     * 5's other two cases, an EMPTY ref and a CJK ref, sit beside them: neither has any character in
     * {@code REPLICA_REF}'s class at all.
     */
    @Test
    void aRefContainingCharactersOutsideReplicaRefIsRefusedByResolutionIsEvidence() {
        assertResolutionRefused("a ref with a space, quotes, = and an accented Latin character",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": "
                                + "[{\"ref\": \"r1 \\\"q\\\" \\\\ = café\", \"restarted\": true, "
                                + "\"observedHash\": null}]}"));
        assertResolutionRefused("a ref with an embedded newline",
                resolved().addValue("resolution_observed",
                        "{\"stagedGeneration\": 1, \"replicas\": "
                                + "[{\"ref\": \"r1\\nwith-a-newline\", \"restarted\": true, "
                                + "\"observedHash\": null}]}"));
        assertResolutionRefused("an empty ref",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"\", \"restarted\": true, \"observedHash\": null}]}"));
        assertResolutionRefused("a CJK ref",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"日本語\", \"restarted\": true, \"observedHash\": null}]}"));
    }

    /**
     * Obligation 1: the two parity negatives that motivated this amendment — the write-direction
     * gap measured before it: PostgreSQL's own {@code \\S} accepted a lone U+001C or U+001F
     * codepoint (no non-whitespace character required by {@code \\S}'s own class), which
     * {@code ReplicaRef}'s {@code isBlank()} would then throw on read. Both codepoints, both
     * operations — the apply branch's {@code replicas[*].ref} and the restart branch's
     * {@code restartsObserved[*].ref} — refused by name, beside the positive control that
     * {@code "cache-engine-1"} in the identical position is accepted (proven already by {@link
     * #aFullyEvidencedResolutionInsertsThePositiveControl} and {@link
     * #aFullyEvidencedRestartResolutionInsertsThePositiveControl}).
     */
    @Test
    void aLoneC0ControlCharacterRefIsRefusedByResolutionIsEvidenceForBothOperations() {
        assertResolutionRefused("a lone U+001C ref, apply branch",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"\\u001c\", \"restarted\": true, "
                        + "\"observedHash\": null}]}"));
        assertResolutionRefused("a lone U+001F ref, apply branch",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"\\u001f\", \"restarted\": true, "
                        + "\"observedHash\": null}]}"));
        assertResolutionRefused("a lone U+001C ref, restart branch",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"ref\": \"\\u001c\", \"restarted\": true}]}"));
        assertResolutionRefused("a lone U+001F ref, restart branch",
                resolvedRestart().addValue("resolution_observed",
                        "{\"restartsObserved\": [{\"ref\": \"\\u001f\", \"restarted\": true}]}"));
    }

    /** Obligation 6, the REFUSED edge of {@code REPLICA_REF}'s {1,255} length bound (the ACCEPTED
     * edge, 255, is {@link #everyLegitimateApplyReplicaShapeIsAcceptedByResolutionIsEvidence}). */
    @Test
    void aRefLongerThan255CharactersIsRefusedByResolutionIsEvidence() {
        String pastTheLengthBoundary256 = "a".repeat(256);
        assertResolutionRefused("a 256-character ref, one past the {1,255} bound",
                resolved().addValue("resolution_observed", "{\"stagedGeneration\": 1, \"replicas\": "
                        + "[{\"ref\": \"" + pastTheLengthBoundary256 + "\", \"restarted\": true, "
                        + "\"observedHash\": null}]}"));
    }

    // -------------------------------------------------------------------------------------------
    // The watermark tables' own CHECKs.
    // -------------------------------------------------------------------------------------------

    @Test
    void theInstanceWideSentinelIsRefusedByRealPlacementsOnly() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "real_placements_only",
                () -> insertWatermark(new UUID(0L, 0L), 1L));

        insertWatermark(UUID.randomUUID(), 1L);
    }

    @Test
    void aZeroPlacementGenerationIsRefusedByPlacementWatermarkPositive() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_watermark_positive",
                () -> insertWatermark(UUID.randomUUID(), 0L));
    }

    @Test
    void aNegativeInstanceWatermarkIsRefusedByInstanceWatermarkNonneg() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_watermark_nonneg",
                () -> runtime.update("INSERT INTO sm_data.apply_scope"
                                + " (tenant_id, instance_id, instance_watermark)"
                                + " VALUES (:t, :i, -1)",
                        new MapSqlParameterSource().addValue("t", tenant)
                                .addValue("i", UUID.randomUUID())));
    }

    // -------------------------------------------------------------------------------------------
    // The other two tables' enum CHECKs.
    // -------------------------------------------------------------------------------------------

    @Test
    void aStepOutsideTheEnumIsRefusedByStepKnown() {
        UUID applyId = insertOpenRequest();
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "step_known",
                () -> runtime.update("INSERT INTO sm_data.apply_step_intent"
                                + " (intent_id, tenant_id, apply_id, replica_ref, step,"
                                + "  idempotency_key, config_hash, created_at)"
                                + " VALUES (:id, :t, :a, 'r1', 'DANCE', :k, NULL, now())",
                        new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                                .addValue("t", tenant).addValue("a", applyId)
                                .addValue("k", "idem-" + UUID.randomUUID())));
    }

    @Test
    void anOutcomeOutsideTheEnumIsRefusedByOutcomeKnown() {
        UUID applyId = insertOpenRequest();
        UUID intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent"
                        + " (intent_id, tenant_id, apply_id, replica_ref, step, idempotency_key,"
                        + "  config_hash, created_at)"
                        + " VALUES (:id, :t, :a, 'r1', 'MATERIALIZE', :k, 'sha256:aaa', now())",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant)
                        .addValue("a", applyId).addValue("k", "idem-" + UUID.randomUUID()));

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "outcome_known",
                () -> runtime.update("INSERT INTO sm_data.apply_attempt"
                                + " (attempt_id, tenant_id, apply_id, intent_id, step, attempt_no,"
                                + "  started_at, step_deadline, outcome)"
                                + " VALUES (:at, :t, :a, :i, 'MATERIALIZE', 1, now(),"
                                + " now() + interval '1 minute', 'MAYBE')",
                        new MapSqlParameterSource().addValue("at", UUID.randomUUID())
                                .addValue("t", tenant).addValue("a", applyId)
                                .addValue("i", intentId)));
    }

    // -------------------------------------------------------------------------------------------

    private static void insert(MapSqlParameterSource params) {
        runtime.update(INSERT_REQUEST, params);
    }

    private static void assertResolutionRefused(String what, MapSqlParameterSource params) {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_is_evidence",
                () -> insert(params), "resolution_is_evidence must refuse " + what);
    }

    /**
     * A row that satisfies EVERY CHECK on {@code apply_request} at once — the baseline each probe
     * spoils in exactly one place.
     *
     * <p>{@code operation = 'apply'} forces a generation ({@code apply_has_generation}) and a strategy
     * ({@code lifecycle_has_strategy}); {@code manual} is legal for an apply
     * ({@code restart_not_manual} fences only restarts) and dodges {@code full_outage_confirmed};
     * {@code REQUESTED} forces {@code terminal_at IS NULL} ({@code terminal_has_timestamp}) and
     * satisfies {@code state_fits_operation}; all-NULL resolution columns satisfy both resolution
     * CHECKs. A fresh {@code placement_id} per probe keeps {@code apply_inflight_fence} out of the
     * picture, so a CHECK failure is never mistaken for a fence collision.
     */
    private static MapSqlParameterSource legalRequest() {
        return new MapSqlParameterSource()
                .addValue("apply_id", UUID.randomUUID())
                .addValue("tenant_id", tenant)
                .addValue("instance_id", instance)
                .addValue("placement_id", UUID.randomUUID())
                .addValue("operation", "apply")
                .addValue("generation", 1L)
                .addValue("strategy", "manual")
                .addValue("outage_confirmed_by", null)
                .addValue("requested_by", "operator-1")
                .addValue("requested_at", OffsetDateTime.now())
                .addValue("state", "REQUESTED")
                .addValue("terminal_at", null)
                .addValue("resolved_by", null)
                .addValue("resolution_outcome", null)
                .addValue("resolution_observed", null);
    }

    /** The legal row walked all the way to a fully evidenced {@code INDETERMINATE_RESOLVED}. */
    private static MapSqlParameterSource resolved() {
        return legalRequest()
                .addValue("state", "INDETERMINATE_RESOLVED")
                .addValue("terminal_at", OffsetDateTime.now())
                .addValue("resolved_by", "duty-operator")
                .addValue("resolution_outcome", "applied")
                .addValue("resolution_observed", GOOD_EVIDENCE);
    }

    /**
     * The restart-shaped counterpart of {@link #legalRequest()} — {@code BE-D17}'s other operation.
     * No generation ({@code apply_has_generation}), a non-manual ladder strategy
     * ({@code restart_not_manual}), otherwise identical.
     */
    private static MapSqlParameterSource legalRestartRequest() {
        return legalRequest()
                .addValue("operation", "restart")
                .addValue("generation", null)
                .addValue("strategy", "rolling-health-gated");
    }

    /** The restart row walked all the way to a fully evidenced {@code INDETERMINATE_RESOLVED}. */
    private static MapSqlParameterSource resolvedRestart() {
        return legalRestartRequest()
                .addValue("state", "INDETERMINATE_RESOLVED")
                .addValue("terminal_at", OffsetDateTime.now())
                .addValue("resolved_by", "duty-operator")
                .addValue("resolution_outcome", "applied")
                .addValue("resolution_observed", GOOD_RESTART_EVIDENCE);
    }

    private static void insertWatermark(UUID placement, long generation) {
        runtime.update("INSERT INTO sm_data.placement_watermark"
                        + " (tenant_id, instance_id, placement_id, generation)"
                        + " VALUES (:t, :i, :p, :g)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("p", placement).addValue("g", generation));
    }

    /** A minimal open request the intent/attempt probes can hang off. */
    private static UUID insertOpenRequest() {
        MapSqlParameterSource params = legalRequest();
        insert(params);
        return (UUID) params.getValue("apply_id");
    }
}
