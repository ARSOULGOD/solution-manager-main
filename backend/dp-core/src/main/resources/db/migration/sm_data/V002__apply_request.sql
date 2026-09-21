-- Three-valued logic is the standing enemy throughout: an absent jsonb key yields SQL NULL,
-- `true = NULL` is NULL, and a NULL CHECK ADMITS (measured on 14.24) — hence coalesce() on every
-- jsonb-derived clause.

-- Control history: one row per operator intent (apply, restart or stop). Audit-grade. Long-lived.
CREATE TABLE sm_data.apply_request (
    apply_id        uuid PRIMARY KEY,          -- UUIDv7: time-ordered, index-friendly
    tenant_id       uuid        NOT NULL,      -- canonical tenant key (architecture §3.1 seam 1; the
                                               -- BE-D4 topic slug is a separate, derived name)
    instance_id     uuid        NOT NULL,
    placement_id    uuid        NOT NULL,      -- nil UUID = the explicit instance-wide sentinel
                                               -- (never SQL NULL: NULL keys defeat the fences).
                                               -- No referential guard exists for it yet — routed,
                                               -- with the placements table, §8 row 13
    operation       text        NOT NULL
        CONSTRAINT op_known CHECK (operation IN ('apply','restart','stop')),
    generation      bigint
        CONSTRAINT generation_positive CHECK (generation IS NULL OR generation > 0),
    strategy        text
        CONSTRAINT strategy_known CHECK (strategy IS NULL OR strategy IN
            ('manual','rolling-health-gated','rolling-no-validation','restart-all','scheduled')),
                                               -- mirrors SM-D15's ladder verbatim (a ladder change is
                                               -- a reviewed migration); whether the PACKAGE supports
                                               -- the value is admission-checked — package-relative,
                                               -- inexpressible statically
    outage_confirmed_by text,
    requested_by    text        NOT NULL,      -- principal id — never a display name
    requested_at    timestamptz NOT NULL,
    state           text        NOT NULL
        CONSTRAINT state_known CHECK (state IN ('REQUESTED','RUNNING','STAGED','APPLIED',
            'FAILED_NO_EFFECT','FAILED_ROLLED_BACK','CONFIG_DIVERGED','SUPERSEDED',
            'APPLY_INDETERMINATE','INDETERMINATE_RESOLVED','STOPPED','STOP_FAILED')),
    terminal_at     timestamptz,
    resolved_by     text,
    resolution_outcome text
        CONSTRAINT resolution_known CHECK (resolution_outcome IN ('applied','not_applied')),
    resolution_observed jsonb,
    resolution_note text,
    attempt_summary jsonb,
    UNIQUE (tenant_id, apply_id),
    -- FK to the scope row (added below apply_scope's creation in the real migration order; shown
    -- here for reading coherence): a request cannot exist without its serialization point, so the
    -- admission discipline is structural, not conventional. The admission transaction's scope-row
    -- upsert precedes the request insert anyway.
    -- FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.apply_scope (tenant_id, instance_id)
    CONSTRAINT apply_has_generation   CHECK ((operation = 'apply') = (generation IS NOT NULL)),
    CONSTRAINT lifecycle_has_strategy CHECK ((operation IN ('apply','restart')) = (strategy IS NOT NULL)),
    CONSTRAINT restart_not_manual     CHECK (operation <> 'restart' OR strategy <> 'manual'),
    CONSTRAINT state_fits_operation   CHECK (CASE operation
            WHEN 'stop'    THEN state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')
            WHEN 'restart' THEN state NOT IN ('STAGED','STOPPED','STOP_FAILED')
            ELSE                state NOT IN ('STOPPED','STOP_FAILED') END),
    -- BE-D16: STAGED is exclusively the manual strategy's terminal state — an implication, not a
    -- biconditional (a manual apply is REQUESTED/RUNNING before it ever reaches STAGED). Self-defending
    -- (coalesce'd) rather than resting on state_fits_operation's stop/restart branches, whose STAGED
    -- exclusion is now exactly as redundant with THIS CHECK as the other branch always was (design
    -- §3.2, R3 correction) — the stop branch stays load-bearing for every OTHER state it excludes,
    -- not for STAGED.
    CONSTRAINT staged_requires_manual CHECK (state <> 'STAGED' OR coalesce(strategy = 'manual', false)),
    CONSTRAINT terminal_has_timestamp CHECK
            ((state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')) = (terminal_at IS NULL)),
    CONSTRAINT full_outage_confirmed  CHECK
            (strategy IS DISTINCT FROM 'restart-all' OR outage_confirmed_by IS NOT NULL),
    -- Resolution is EVIDENCE: resolution_outcome = 'applied' feeds rule 1's monotonicity and must
    -- never rest on an unsupported claim. Every clause coalesced — '{}' passed the uncoalesced form
    -- via NULL propagation, measured. Shape is OPERATION-SPECIFIC (BE-D17): for 'apply', DB proves
    -- stagedGeneration EQUALS the request's own generation (jsonb-to-jsonb, no cast — a cast can
    -- THROW ahead of its own type guard under undefined AND-order, measured) and replicas is a
    -- NON-EMPTY ARRAY (compared against '[]'::jsonb, never jsonb_array_length, which itself throws
    -- on a non-array operand); for 'restart', DB proves NO stagedGeneration key at all and
    -- restartsObserved is a NON-EMPTY ARRAY, by the same throw-free pattern. Replica/observation-
    -- ELEMENT typing is now ALSO DB-enforced (BE-D18, design §3.1's R6 correction of this comment):
    -- ten further jsonb_path_exists(…, silent => true) predicates below (six for 'apply', four for
    -- 'restart') refuse a malformed element, an absent-but-mandatory 'observedHash' key on an apply
    -- element (its VALUE stays nullable), an unexpected element or top-level key, a malformed hash,
    -- and the lax-mode array-unwrap escape every one of those would otherwise leave open (a
    -- filter applied directly to an array-valued element auto-unwraps under lax mode, so a dedicated
    -- '.type()' predicate — which never unwraps — closes it). The engine's typed resolver (own
    -- refusal tests) stays the construction-time first line; this CHECK is no longer merely
    -- evidence-shaped, it is element-complete.
    CONSTRAINT resolution_is_evidence CHECK ((state = 'INDETERMINATE_RESOLVED') = (
            resolved_by IS NOT NULL
            AND resolution_outcome IS NOT NULL
            AND coalesce(jsonb_typeof(resolution_observed) = 'object', false)
            AND CASE operation
                WHEN 'apply' THEN
                    coalesce(resolution_observed -> 'stagedGeneration' = to_jsonb(generation), false)
                    AND coalesce(jsonb_typeof(resolution_observed -> 'replicas') = 'array', false)
                    AND coalesce(resolution_observed -> 'replicas' <> '[]'::jsonb, false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                            && @.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$" && @.restarted.type() == "boolean"
                            && exists(@.observedHash)))',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].keyvalue() ? (@.key != "ref" && @.key != "restarted"
                            && @.key != "observedHash")', '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].observedHash.type() ? (@ != "null" && @ != "string")',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].observedHash ? (!(@.type() == "null" ||
                            (@.type() == "string" && @ like_regex "^[a-z0-9]+:[0-9a-f]{16,128}$")))',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.keyvalue() ? (@.key != "stagedGeneration" && @.key != "replicas")',
                        '{}'::jsonb, true), false)
                WHEN 'restart' THEN
                    NOT coalesce(jsonb_exists(resolution_observed, 'stagedGeneration'), false)
                    AND coalesce(jsonb_typeof(resolution_observed -> 'restartsObserved') = 'array', false)
                    AND coalesce(resolution_observed -> 'restartsObserved' <> '[]'::jsonb, false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.restartsObserved[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.restartsObserved[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                            && @.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$" && @.restarted.type() == "boolean"))',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.restartsObserved[*].keyvalue() ? (@.key != "ref" && @.key != "restarted")',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.keyvalue() ? (@.key != "restartsObserved")', '{}'::jsonb, true), false)
                ELSE false
            END)),
    CONSTRAINT resolution_only_when_resolved CHECK (state = 'INDETERMINATE_RESOLVED'
            OR (resolved_by IS NULL AND resolution_outcome IS NULL
                AND resolution_observed IS NULL AND resolution_note IS NULL))
);

-- The fences, NAMED (refusals surface by index name; the migration tests are written against these):
CREATE UNIQUE INDEX apply_inflight_fence ON sm_data.apply_request
    (tenant_id, instance_id, placement_id)
    WHERE state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE') AND operation <> 'stop';
CREATE UNIQUE INDEX stop_inflight_fence ON sm_data.apply_request
    (tenant_id, instance_id, placement_id)
    WHERE state IN ('REQUESTED','RUNNING') AND operation = 'stop';
CREATE UNIQUE INDEX staged_fence ON sm_data.apply_request
    (tenant_id, instance_id, placement_id)
    WHERE state = 'STAGED';
    -- at most one STAGED per scope: admission marks the elder SUPERSEDED BEFORE inserting (the one
    -- terminal transition the trigger below permits), and this fence turns forgetting that order
    -- into a unique violation instead of silent accumulation
CREATE INDEX apply_history ON sm_data.apply_request (tenant_id, instance_id, requested_at DESC);

-- Per-table runtime grants (each table's migration carries its own):
GRANT SELECT,
      INSERT (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,
              outage_confirmed_by, requested_by, requested_at, state, terminal_at, resolved_by,
              resolution_outcome, resolution_observed, attempt_summary),
      UPDATE (state, terminal_at, resolved_by, resolution_outcome,
              resolution_observed, attempt_summary)
    ON sm_data.apply_request TO sm_dp_runtime;
    -- COLUMN-scoped UPDATE: identity (tenant_id, instance_id, placement_id, operation, generation,
    -- strategy, requested_by, requested_at, outage_confirmed_by) is not runtime-writable. Measured
    -- hazard this closes: the runtime credential re-tenanting rows (an I5 write surface) and
    -- re-attributing requested_by on terminal rows. resolution_note is DELIBERATELY absent from
    -- BOTH lists — INSERT is column-scoped too, or a row could be born with a note: it is the
    -- schema's one free-text column and v1 writes no free text (I6 — the residual table); the
    -- runtime credential cannot write it at all, so no engine path, present or mistaken, can. A
    -- comments feature adds the column to both grants in its own migration, with its own design.
