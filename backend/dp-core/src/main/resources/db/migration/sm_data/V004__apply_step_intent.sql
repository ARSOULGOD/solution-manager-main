-- Step intents: the IMMUTABLE identity substrate of §2.2 rule 2 — exactly ONE row per logical
-- (apply, step, replica), holding the DP-assigned key and the INTENDED hash. Immutable by the DCL
-- at the end of this phase: the runtime role cannot UPDATE or DELETE here.
CREATE TABLE sm_data.apply_step_intent (
    intent_id       uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    apply_id        uuid        NOT NULL,
    replica_ref     text        NOT NULL,      -- adapter vocabulary; the sentinel '@placement' marks
                                               -- placement-scoped steps
    step            text        NOT NULL
        CONSTRAINT step_known CHECK (step IN ('PREFLIGHT','MATERIALIZE','LIFECYCLE','VERIFY',
                                              'ROLLBACK','ROLLBACK_ASSESS','STOP')),
    idempotency_key text        NOT NULL UNIQUE,
    config_hash     text,
    created_at      timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, apply_id) REFERENCES sm_data.apply_request (tenant_id, apply_id),
    UNIQUE (tenant_id, apply_id, step, replica_ref),
    UNIQUE (tenant_id, apply_id, intent_id),   -- target of apply_attempt's triple FK
    UNIQUE (intent_id, step),                  -- target of apply_attempt's step-consistency FK
    CONSTRAINT hash_on_config_steps CHECK
        ((step IN ('MATERIALIZE','VERIFY','ROLLBACK')) = (config_hash IS NOT NULL)),
    CONSTRAINT placement_scoped_steps CHECK
        ((step IN ('PREFLIGHT','ROLLBACK','ROLLBACK_ASSESS')) = (replica_ref = '@placement'))
);

GRANT SELECT, INSERT ON sm_data.apply_step_intent TO sm_dp_runtime;  -- IMMUTABLE: no UPDATE/DELETE
