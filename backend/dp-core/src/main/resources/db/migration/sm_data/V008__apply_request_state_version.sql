-- PLAN 010 T2.2 — apply_request carries the ONE stateVersion stamp (OR-35), and an apply_scope row may only exist
-- for a declared instance. Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql).
--
-- 1. REFUSE A NON-EMPTY JOURNAL. No backfill (M6, measured against V006): forbid_terminal_regression lets a TERMINAL
--    row change only when `to_jsonb(NEW) - 'attempt_summary' = to_jsonb(OLD) - 'attempt_summary'`, so an UPDATE that
--    writes state_version on an existing terminal row is refused by the guard itself — and a legacy row for an
--    undeclared instance could not be stamped anyway (step 3 raises when the owning instance_state row is missing).
--    Pre-production there is no customer database: a development database carrying demo-era journal rows is
--    RECREATED (drop and re-run Phase 0 + Phase 1), never patched. The message names the procedure, never a row (I6).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sm_data.apply_request) THEN
        RAISE EXCEPTION 'V008 refuses to run over a non-empty sm_data.apply_request: recreate the sm_data database '
            'and re-run Phase 0 and Phase 1 (pre-production: there is no backfill, the terminal guard forbids it)';
    END IF;
END $$;

-- 2. The column. Legal because the table is empty (checked above, in this same transaction).
ALTER TABLE sm_data.apply_request ADD COLUMN state_version bigint NOT NULL;

-- 3. The stamp. ONE FUNCTION PER OWNING TABLE (V003's measured reason). BEFORE INSERT OR UPDATE it
--      (a) on an UPDATE that changes nothing OBSERVABLE — the compaction fold (attempt_summary and only that) or a
--          write that changes nothing at all — keeps the old stamp, takes no lock and draws no value. A compaction is
--          not an observable change, so not stamping it is OR-35's "when it changes", not an exception to it. This is
--          also what makes this trigger and V006's apply_request_terminal_guard ORDER-INDEPENDENT: on the fold
--          neither changes state_version, so the guard's `to_jsonb(NEW) - 'attempt_summary' = to_jsonb(OLD) - ...`
--          comparison holds whichever fires first; on STAGED -> SUPERSEDED the guard returns NEW before comparing; on
--          a non-terminal row the guard does not compare. PostgreSQL's firing order among same-event triggers
--          (alphabetical by name) is therefore not relied on.
--      (b) otherwise takes the owning instance_state row FOR UPDATE (the recorded commit-order rule: lock order is
--          apply_scope -> instance_state -> row, so a request stamp within one instance is in commit order) and then
--          draws the next value. It RAISES when that row is missing — it never stamps silently, so a request can
--          never carry a stamp from an instance the DP does not know. (The apply_scope foreign key below is the
--          independent second enforcer; admission's own check is the third, and the cleanest.)
CREATE FUNCTION sm_data.stamp_apply_request_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'attempt_summary' - 'state_version') = (to_jsonb(OLD) - 'attempt_summary' - 'state_version') THEN
        NEW.state_version := OLD.state_version;
        RETURN NEW;
    END IF;
    PERFORM 1 FROM sm_data.instance_state
        WHERE tenant_id = NEW.tenant_id AND instance_id = NEW.instance_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'apply_request names an instance the data plane has not declared'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'apply_request_has_instance';
    END IF;
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER apply_request_stamp BEFORE INSERT OR UPDATE ON sm_data.apply_request
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_apply_request_state_version();

-- 4. NO GRANT CHANGE (M5, measured in V007): the runtime role holds USAGE on the sequence and column privileges on
--    apply_request that do NOT include state_version, so the trigger sets it and an explicit write is refused 42501.

-- 5. An apply_scope row may only exist for a declared instance. NOT VALID (M6, plan 010; PostgreSQL 14 ALTER TABLE:
--    "The constraint will still be enforced against subsequent inserts or updates ... the database will not assume
--    that the constraint holds for all rows in the table"; ADD FOREIGN KEY takes SHARE ROW EXCLUSIVE on both tables):
--    new rows are checked, pre-existing scope rows are not scanned.
ALTER TABLE sm_data.apply_scope ADD CONSTRAINT scope_has_instance
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.instance_state (tenant_id, instance_id) NOT VALID;
