-- PLAN 008 S2, BE-D44 (owner ruling OR-121, OQ-8.2 (a)): apply_step_intent becomes placement-aware, which closes BOTH
-- clauses of BE-D23 in the DDL. Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so the column,
-- the constraint and the function are born owned by sm_dp_owner.
--
-- WHY. The identity was UNIQUE (tenant_id, apply_id, step, replica_ref). Clause 1: placement_scoped_steps pins
-- replica_ref to '@placement' for PREFLIGHT, ROLLBACK and ROLLBACK_ASSESS, so one apply_id held at most one of each.
-- Clause 2: replica_ref is adapter vocabulary (BE-D19) and the SSH-Docker adapter's is the container name, which is
-- unique per TARGET only (placement_container_once, V007), so an instance-wide stop over two placements on two hosts
-- that both name a container `sm-cache` landed STOP_FAILED / stop.replica.label.collision having stopped nothing.
-- The intent now carries the placement it addresses, and the identity is (tenant_id, apply_id, placement_id, step,
-- replica_ref). BE-D23's prohibition on encoding a placement INTO replica_ref is carried forward unchanged: this is a
-- KEY change, never a value change.
--
-- WHAT STAYS. A placement-scoped request's intents all carry that request's own placement (the trigger below), so for
-- such a request the old identity holds exactly as before. Its idempotency key keeps the format applyId:step:replica
-- (ApplyExecutor.idempotencyKey), which is what lets a request admitted BEFORE this migration resume onto its own rows:
-- StepIntentService.compare requires key equality. Only an instance-wide request's key names the placement. The global
-- UNIQUE on idempotency_key is untouched (the two key formats cannot collide: one apply_id is either instance-wide or
-- it is not). The INSERT-only grant is untouched: the runtime role still cannot UPDATE or DELETE here.
--
-- 1. REFUSE the one case that cannot be back-filled: an instance-wide request in flight. Its STOP intents were written
--    without the placement they addressed (TargetReplica carried it in memory only), and resume would re-enumerate and
--    re-insert. V008's pattern: a fixed sentence naming the procedure, never a row (I6). Placement-scoped requests in
--    flight are NOT refused; they are back-filled below and resume onto their own rows.
-- The check is preceded by a SHARE lock on apply_request (held to the end of this transaction): admission INSERTs take
-- ROW EXCLUSIVE, which conflicts with SHARE, so no instance-wide request can be admitted between the check and the
-- ADD COLUMN below. Reads are unaffected; writers queue for the length of this migration.
LOCK TABLE sm_data.apply_request IN SHARE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sm_data.apply_request
               WHERE placement_id = '00000000-0000-0000-0000-000000000000'
                 AND state IN ('REQUESTED', 'RUNNING', 'APPLY_INDETERMINATE')) THEN
        RAISE EXCEPTION 'V021 refuses to run while an instance-wide request is in flight: let the instance-wide stop land, '
            'or wait for resume to strand it, then re-run (docs/runbooks/operations.md, "Upgrade across the '
            'placement-aware apply journal")';
    END IF;
END $$;

-- 2. The column, then the back-fill: a placement-scoped request's intents get the request's placement. The migration
--    owner can UPDATE this table (the runtime role cannot, V004's DCL) and no trigger guards it; V021 touches no
--    apply_request row. Intents of TERMINAL instance-wide stops keep the nil value, STATED HERE: their placements were
--    never persisted and cannot be derived, and a terminal request admits no new intent (obligation (q),
--    StepIntentService.ensureIntent), so those rows are never re-found and never re-inserted. They are history.
ALTER TABLE sm_data.apply_step_intent ADD COLUMN placement_id uuid;
UPDATE sm_data.apply_step_intent i SET placement_id = r.placement_id
    FROM sm_data.apply_request r
    WHERE r.tenant_id = i.tenant_id AND r.apply_id = i.apply_id;
ALTER TABLE sm_data.apply_step_intent ALTER COLUMN placement_id SET NOT NULL;

-- 3. The new identity BEFORE the old one is dropped, so there is no moment without an arbiter. Strictly weaker than the
--    old key as a table-wide statement (it adds a column) and exactly as strong for every placement-scoped request, by
--    the trigger below; for an instance-wide request it is the point.
ALTER TABLE sm_data.apply_step_intent ADD CONSTRAINT apply_step_intent_placement_identity_key
    UNIQUE (tenant_id, apply_id, placement_id, step, replica_ref);
ALTER TABLE sm_data.apply_step_intent DROP CONSTRAINT apply_step_intent_tenant_id_apply_id_step_replica_ref_key;

-- 4. What an intent's placement MUST be, refused in the database as the second enforcer (the engine passes the replica's
--    own placement, which is the first). Refusals are P0001 with a fixed sentence that names no value (I6).
--      * never the nil sentinel, on every INSERT (the sentinel marks a REQUEST as instance-wide; an intent addresses a
--        real placement). Existing nil rows are history, not inserts: the trigger is BEFORE INSERT only;
--      * for a placement-scoped request: that request's own placement;
--      * for an instance-wide request: a live OR retired placement of the request's instance (a retired placement is
--        history an instance-wide stop's journal may still name; liveness is the engine's and the registry's concern).
--    A NULL placement or an unknown request passes through to the NOT NULL and the foreign key, which refuse them by name.
--    Tenant is bound in every lookup (I5). Runs with the invoker's privileges: the runtime role holds SELECT on both
--    tables it reads.
CREATE FUNCTION sm_data.apply_step_intent_placement_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    request_placement uuid;
    request_instance  uuid;
BEGIN
    IF NEW.placement_id IS NULL THEN
        RETURN NEW;                                  -- NOT NULL refuses it, by column
    END IF;
    IF NEW.placement_id = '00000000-0000-0000-0000-000000000000' THEN
        RAISE EXCEPTION 'apply_step_intent placement_id may not be the instance-wide sentinel';
    END IF;
    SELECT r.placement_id, r.instance_id INTO request_placement, request_instance
        FROM sm_data.apply_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.apply_id = NEW.apply_id;
    IF NOT FOUND THEN
        RETURN NEW;                                  -- the foreign key refuses an unknown request, by name
    END IF;
    IF request_placement <> '00000000-0000-0000-0000-000000000000' THEN
        IF NEW.placement_id <> request_placement THEN
            RAISE EXCEPTION 'apply_step_intent placement_id must be the request''s own placement';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM sm_data.placement p
                      WHERE p.tenant_id = NEW.tenant_id AND p.placement_id = NEW.placement_id
                        AND p.instance_id = request_instance) THEN
        RAISE EXCEPTION 'apply_step_intent placement_id must be a placement of the request''s instance';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_step_intent_placement_identity BEFORE INSERT ON sm_data.apply_step_intent
    FOR EACH ROW EXECUTE FUNCTION sm_data.apply_step_intent_placement_identity();

-- LOCK CLASS: every statement above takes ACCESS EXCLUSIVE on sm_data.apply_step_intent (ADD COLUMN, SET NOT NULL, ADD
-- and DROP CONSTRAINT, and the unique index build), held to the end of the migration's transaction; CREATE TRIGGER takes
-- SHARE ROW EXCLUSIVE. The UPDATE rewrites every existing intent row once. The table is insert-only and small (a handful
-- of rows per request), so this is a short maintenance-window migration, not a background one; MF-1's row count on the
-- owner's database is the operator's own pre-check (the runbook gives the query).
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand, in this order:
-- DROP TRIGGER apply_step_intent_placement_identity ON sm_data.apply_step_intent; DROP FUNCTION
-- sm_data.apply_step_intent_placement_identity(); re-create UNIQUE (tenant_id, apply_id, step, replica_ref) (the old
-- name is apply_step_intent_tenant_id_apply_id_step_replica_ref_key); then DROP CONSTRAINT
-- apply_step_intent_placement_identity_key and DROP COLUMN placement_id; finally delete the V021 row from
-- flyway_schema_history. One-way once two intents of one (tenant_id, apply_id, step, replica_ref) are journalled under
-- different placements (an instance-wide stop over two same-labelled placements): re-creating the old key over them
-- fails.
