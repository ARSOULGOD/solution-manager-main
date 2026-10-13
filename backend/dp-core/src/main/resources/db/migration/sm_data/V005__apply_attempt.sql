-- Execution journal: one row per RETRY of one intent. Diagnostic-grade. Prunable.
CREATE TABLE sm_data.apply_attempt (
    attempt_id      uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    apply_id        uuid        NOT NULL,
    intent_id       uuid        NOT NULL,
    step            text        NOT NULL,      -- denormalized from the intent, consistent BY SHAPE
                                               -- via the (intent_id, step) FK — so per-step CHECKs
                                               -- on attempts are expressible
    FOREIGN KEY (tenant_id, apply_id, intent_id)
        REFERENCES sm_data.apply_step_intent (tenant_id, apply_id, intent_id),
    FOREIGN KEY (intent_id, step) REFERENCES sm_data.apply_step_intent (intent_id, step),
    FOREIGN KEY (tenant_id, apply_id) REFERENCES sm_data.apply_request (tenant_id, apply_id),
    attempt_no      smallint    NOT NULL
        CONSTRAINT attempt_no_positive CHECK (attempt_no >= 1),
                                               -- lower bound the DB's; the configured cap is the
                                               -- engine's (obligation (m)); smallint is a deliberate
                                               -- last-ditch backstop at 32767
    pre_baseline    jsonb,
    CONSTRAINT baseline_only_for_lifecycle CHECK (step = 'LIFECYCLE' OR pre_baseline IS NULL),
    started_at      timestamptz NOT NULL,
    ended_at        timestamptz,
    CONSTRAINT attempt_time_ordered CHECK (ended_at IS NULL OR ended_at >= started_at),
    step_deadline   timestamptz NOT NULL,
    outcome         text
        CONSTRAINT outcome_known CHECK (outcome IN ('SUCCEEDED','FAILED','TIMED_OUT')),
    observed_hash   text,                      -- read-back EVIDENCE, never intent
    error_class     text,                      -- classified, greppable; never raw secrets (I6 —
                                               -- engine obligation: classification, not free text)
    runtime_ref     jsonb,
    UNIQUE (intent_id, attempt_no)
);
CREATE INDEX attempt_prune ON sm_data.apply_attempt (tenant_id, started_at);
CREATE UNIQUE INDEX attempt_open_fence ON sm_data.apply_attempt (intent_id)
    WHERE outcome IS NULL;
    -- BE-D20: at most one OPEN attempt per intent, regardless of attempt_no — closes the gap
    -- UNIQUE (intent_id, attempt_no) leaves open (a sequential second open, no numbering collision
    -- at all). A legal retry stays legal once the prior attempt is closed (outcome IS NOT NULL).

GRANT SELECT, INSERT, UPDATE, DELETE ON sm_data.apply_attempt TO sm_dp_runtime;  -- close-out + §3.3 pruning
