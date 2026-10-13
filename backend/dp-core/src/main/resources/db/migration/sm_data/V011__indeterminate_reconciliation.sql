-- PLAN 010 T6.8 (owner ruling 2026-09-29, verify-then-retry; Q-R1..Q-R4 = OR-63): the engine reconciles an
-- APPLY_INDETERMINATE request against a fresh host observation. Run by Flyway as sm_dp_migrator after
-- `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by sm_dp_owner.
--
-- Two things, and nothing else (Step 0 (a), measured against V002 and V006):
--   * the APPLY_INDETERMINATE -> RUNNING re-drive is ALREADY LEGAL in the DDL: state_fits_operation allows RUNNING for an
--     'apply'; terminal_has_timestamp wants terminal_at NULL for both states; apply_inflight_fence lists both; and
--     forbid_terminal_regression exempts an OLD.state of APPLY_INDETERMINATE. So there is NO state-CHECK change here.
--   * the bookkeeping the reconciler needs to be bounded (apply_reconciliation) and the one observable fact a give-up
--     changes on the request itself (apply_request.reconcile_class).

-- 1. Per-request reconciliation bookkeeping. NOT a stamped owning row: the counters are bookkeeping and change nothing an
--    operator can observe, so no state_version trigger is attached (flagged in the decision draft). The OBSERVABLE change
--    of a give-up is the request's classifier, written below on apply_request, which the T2.2 stamp trigger stamps.
--
--    A row is DUE when gave_up_at IS NULL AND next_attempt_at <= now(): a given-up request is never polled again, so a
--    tenant the worker keeps enumerating costs one indexed read per poll. gave_up_at also closes a row that has nothing
--    left to reconcile (a confirmation, or an operator who resolved first); last_class says which.
CREATE TABLE sm_data.apply_reconciliation (
    tenant_id              uuid        NOT NULL,
    apply_id               uuid        NOT NULL,
    inspections            integer     NOT NULL DEFAULT 0
        CONSTRAINT reconciliation_inspections_nonnegative CHECK (inspections >= 0),
                                       -- FAILED or UNDECIDED inspections only (Q-R3): a successful inspection is free
    redrives               integer     NOT NULL DEFAULT 0
        CONSTRAINT reconciliation_redrives_nonnegative CHECK (redrives >= 0),
                                       -- re-drives, restart-only or re-apply, counted separately from inspections
    next_attempt_at        timestamptz NOT NULL,
    first_indeterminate_at timestamptz NOT NULL,
                                       -- the age bound (max-redrive-age) runs from here: the first APPLY_INDETERMINATE
                                       -- landing (OutcomeService.land writes the row in the landing transaction); a row a
                                       -- poll or startup sweep creates for a landing it did not witness uses its own clock
    gave_up_at             timestamptz,
    last_class             text
        CONSTRAINT reconciliation_last_class_grammar CHECK (last_class IS NULL OR
            (length(last_class) <= 64 AND last_class ~ '^[a-z][a-z0-9]*(\.[a-z0-9]+)*$')),
    PRIMARY KEY (tenant_id, apply_id),
    CONSTRAINT reconciliation_has_request FOREIGN KEY (tenant_id, apply_id)
        REFERENCES sm_data.apply_request (tenant_id, apply_id)
);

-- The poll's read: due rows of one tenant. Partial, so closed rows cost nothing.
CREATE INDEX apply_reconciliation_due ON sm_data.apply_reconciliation (tenant_id, next_attempt_at)
    WHERE gave_up_at IS NULL;

-- Runtime grants, explicit. INSERT and UPDATE of every bookkeeping column; no DELETE (history), no TRUNCATE. The identity
-- columns are in the INSERT list only: an UPDATE cannot re-target a row to another tenant or request.
GRANT SELECT, INSERT ON sm_data.apply_reconciliation TO sm_dp_runtime;
GRANT UPDATE (inspections, redrives, next_attempt_at, gave_up_at, last_class) ON sm_data.apply_reconciliation TO sm_dp_runtime;

-- 2. The classifier a give-up writes on the request. Nullable; the grammar mirrors dp-core's ErrorClass exactly (a dotted
--    lower-case token of at most 64 characters), as V002 mirrors ValueGrammar: a classifier can never carry text.
ALTER TABLE sm_data.apply_request ADD COLUMN reconcile_class text;
ALTER TABLE sm_data.apply_request ADD CONSTRAINT reconcile_class_grammar CHECK (reconcile_class IS NULL OR
    (length(reconcile_class) <= 64 AND reconcile_class ~ '^[a-z][a-z0-9]*(\.[a-z0-9]+)*$'));

-- The runtime role may set it, on that column only (V002's grant is column-scoped on purpose). The T2.2 stamp trigger
-- sees a changed column that is not the compaction fold, so a give-up is an OBSERVABLE change and draws a new state_version
-- (M-1). The terminal guard refuses the write once a request is terminal, which is what makes an operator's resolution win.
GRANT UPDATE (reconcile_class) ON sm_data.apply_request TO sm_dp_runtime;
