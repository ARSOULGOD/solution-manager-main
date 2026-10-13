-- PLAN 012 T3 (sm_data V023; docs/design/backend/ops-501.md section 4.3 and 4.4, OPS-Q4 (a), OR-121). Run by Flyway as
-- sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by sm_dp_owner. Keyed by
-- tenant_id first (I5); no foreign key leaves sm_data (I7); no secret is stored (I6): ids, a closed vocabulary and the
-- placements' last health state, so there is nowhere to put a value.
--
-- UNDECLARE, THE DATA PLANE'S HALF. The data plane records the intent first (instance_state 'retiring' plus an OPEN
-- instance_retirement row), the control plane releases its governance node, and the data plane finishes ('retired', the
-- placements retired, the row closed). A crash between the steps is recovered by a retry or by the lazy sweep; the sweep
-- can also ROLL BACK a retiring row whose control plane node was never released. This migration is the schema for all of
-- it: the two new lifecycle values, the one guarded backward move, the name freed at 'retired', and the audit-grade record.
-- Nothing is deleted (BE-D32 rule 7): every row moves to a terminal state and stays. The workload on the host is never
-- touched (U-C): nothing here reads or writes a target.

-- ==== 1. the lifecycle vocabulary =================================================================================
-- Same name, so the refusal tests and the readiness check keep looking for `instance_lifecycle`. DROP + ADD rather than
-- ALTER: a CHECK's expression cannot be altered in place. ACCESS EXCLUSIVE on instance_state for the length of the scan
-- (one row per declared instance: a maintenance-window statement, not a background one).
ALTER TABLE sm_data.instance_state DROP CONSTRAINT instance_lifecycle;
ALTER TABLE sm_data.instance_state ADD CONSTRAINT instance_lifecycle CHECK (
    lifecycle IN ('pending', 'active', 'abandoned', 'retiring', 'retired'));

-- ==== 2. a retired instance frees its name (OPS-Q4) ===============================================================
-- OR-88's rule extended: an instance NAME is unique among pending, active and RETIRING instances of its environment. A
-- retiring row still holds its name because it may be rolled back to active, and that rollback must not collide with a
-- declare that took the name meanwhile. A retired or abandoned row holds nothing, and any number may share a name. Same
-- index name (the 23505 mapping looks for `instance_name_once`). DROP INDEX holds ACCESS EXCLUSIVE until the end of this
-- transaction, so no declare can slip between the drop and the create.
DROP INDEX sm_data.instance_name_once;
CREATE UNIQUE INDEX instance_name_once ON sm_data.instance_state (tenant_id, environment_id, name)
    WHERE lifecycle NOT IN ('abandoned', 'retired');

-- ==== 3. instance_retirement - the data plane's audit-grade record of an undeclare ================================
-- One row per undeclare (placement_id NULL = the whole instance) and, from plan 012 T5, one per placement retirement
-- (placement_id set). OPEN until it is closed, once, with an outcome: 'retired' when the undeclare finished, 'rolled-back'
-- when the sweep gave up on it. The row is the data plane's counterpart of apply_request for applies (D10 section 1.4):
-- requested_by is the ORIGINAL requester and never changes, even when a different session resumes the undeclare.
CREATE TABLE sm_data.instance_retirement (
    tenant_id uuid NOT NULL,
    retirement_id uuid NOT NULL,                         -- minted by the data plane
    instance_id uuid NOT NULL,
    placement_id uuid NULL,                              -- NULL: the whole instance; set: that one placement (plan 012 T5)
    requested_by uuid NOT NULL,                          -- the principal id of the session that asked
    requested_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,                        -- the request's X-Correlation-Id
    workload_disposition text NOT NULL
        CONSTRAINT instance_retirement_disposition CHECK (workload_disposition = 'leave-running'),
                                                         -- the only v1 disposition (U-C); a closed CHECK so a later one is a decision
    -- Each placement's last PERSISTED health state at the time, as [{"placementId": <uuid text>, "lastHealth": <state text
    -- or null>}]: ids and the placement_health vocabulary only, an element with any other key is refused. A placement that
    -- was never probed reads null. Computed by the data plane from placement_health in the same statement that inserts the row.
    last_health jsonb NOT NULL
        CONSTRAINT instance_retirement_health_shape CHECK (
            jsonb_typeof(last_health) = 'array'
            AND NOT jsonb_path_exists(last_health, '$[*] ? (
                @.type() != "object"
                || exists(@.keyvalue() ? (@.key != "placementId" && @.key != "lastHealth"))
                || !exists(@.placementId) || !exists(@.lastHealth)
                || @.placementId.type() != "string"
                || !(@.placementId like_regex "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                || (@.lastHealth.type() != "string" && @.lastHealth.type() != "null")
                || (@.lastHealth.type() == "string" && !(@.lastHealth like_regex "^[A-Za-z_]{1,32}$")))')),
    completed_at timestamptz NULL,
    outcome text NULL
        CONSTRAINT instance_retirement_outcome CHECK (outcome IN ('retired', 'rolled-back')),
    state_version bigint NOT NULL,                       -- OR-35: state_version_seq, stamped by trigger
    PRIMARY KEY (tenant_id, retirement_id),
    CONSTRAINT instance_retirement_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id),
    -- MATCH SIMPLE: a NULL placement_id skips the check. That the placement belongs to THIS instance is the stamp trigger's.
    CONSTRAINT instance_retirement_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id),
    -- Closed together or not at all: a completion without an outcome (or the reverse) would be a record that says nothing.
    CONSTRAINT instance_retirement_closed_together CHECK ((completed_at IS NULL) = (outcome IS NULL)),
    -- A placement is retired by one statement, never attempted-and-abandoned: only a whole-instance row can roll back.
    CONSTRAINT instance_retirement_placement_outcome CHECK (placement_id IS NULL OR outcome IS DISTINCT FROM 'rolled-back'));

-- At most ONE open whole-instance retirement per instance: a retry finds the open row and resumes it instead of starting a
-- second one, and a concurrent second begin is refused by the database even if the service's lock were bypassed.
CREATE UNIQUE INDEX instance_retirement_open_once ON sm_data.instance_retirement (tenant_id, instance_id)
    WHERE placement_id IS NULL AND completed_at IS NULL;

-- The stamp (OR-35), ONE FUNCTION PER OWNING TABLE (V003's measured reason). An INSERT and an UPDATE both take the owning
-- instance_state row FOR UPDATE and then the next value of the one sequence (the recorded commit-order rule: within one
-- instance, stamp order is commit order). A placement-scoped row must name a placement OF THIS instance: the foreign keys
-- alone would accept any declared instance with any placement. Refusals carry the constraint name the foreign key would.
CREATE FUNCTION sm_data.stamp_instance_retirement_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM sm_data.instance_state
        WHERE tenant_id = NEW.tenant_id AND instance_id = NEW.instance_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'instance_retirement names an instance the data plane has not declared'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'instance_retirement_has_instance';
    END IF;
    IF NEW.placement_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM sm_data.placement
            WHERE tenant_id = NEW.tenant_id AND placement_id = NEW.placement_id AND instance_id = NEW.instance_id) THEN
        RAISE EXCEPTION 'instance_retirement names a placement the instance does not have'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'instance_retirement_has_placement';
    END IF;
    -- A whole-instance record is BORN OPEN (W2 review W2C-1). Only a placement retirement, one statement, is written already
    -- closed (plan 012 T5). Without this, one INSERT of (placement_id NULL, outcome 'rolled-back', completed_at now()) would
    -- hand the lifecycle trigger below its "closed rolled-back in this transaction" proof while the real open record stays open.
    IF TG_OP = 'INSERT' AND NEW.placement_id IS NULL AND (NEW.completed_at IS NOT NULL OR NEW.outcome IS NOT NULL) THEN
        RAISE EXCEPTION 'a whole-instance instance_retirement is inserted open and closed only by an update'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'instance_retirement_whole_born_open';
    END IF;
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER instance_retirement_stamp BEFORE INSERT OR UPDATE ON sm_data.instance_retirement
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_instance_retirement_state_version();

-- Insert-only except completed_at and outcome, which are set ONCE and TOGETHER: an update must close an open row (it cannot
-- be a no-op, cannot clear a value, cannot touch the second time) and must change nothing else. The stamp is excluded from
-- the comparison because the stamp trigger rewrites it on the same update. A trigger, not only the column grant: the grant
-- guards the runtime role, this guards every other writer, the owner included.
CREATE FUNCTION sm_data.instance_retirement_closed_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.completed_at IS NOT NULL OR OLD.outcome IS NOT NULL THEN
        RAISE EXCEPTION 'an instance_retirement is closed once and never changes again';
    END IF;
    IF NEW.completed_at IS NULL OR NEW.outcome IS NULL THEN
        RAISE EXCEPTION 'an instance_retirement update must close it: completed_at and outcome together';
    END IF;
    IF (to_jsonb(NEW) - 'completed_at' - 'outcome' - 'state_version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'completed_at' - 'outcome' - 'state_version') THEN
        RAISE EXCEPTION 'an instance_retirement may change only completed_at and outcome';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER instance_retirement_guard BEFORE UPDATE ON sm_data.instance_retirement
    FOR EACH ROW EXECUTE FUNCTION sm_data.instance_retirement_closed_once();

-- Grants: INSERT is column-scoped to everything but state_version (the trigger stamps it; an explicit value is refused 42501,
-- measured in V007). completed_at and outcome are insertable so a placement retirement, which is one statement, can be
-- written already closed; the shape CHECKs and the stamp trigger above govern both ways (a whole-instance row is refused
-- if inserted closed, W2C-1). UPDATE reaches the two closing columns and
-- nothing else. No DELETE.
GRANT SELECT ON sm_data.instance_retirement TO sm_dp_runtime;
GRANT INSERT (tenant_id, retirement_id, instance_id, placement_id, requested_by, requested_at, correlation_id,
              workload_disposition, last_health, completed_at, outcome)
    ON sm_data.instance_retirement TO sm_dp_runtime;
GRANT UPDATE (completed_at, outcome) ON sm_data.instance_retirement TO sm_dp_runtime;

-- ==== 4. the lifecycle moves: replace V007's function, whole body ================================================
-- Exactly: pending -> active | abandoned; active -> retiring; retiring -> retired; and retiring -> active ONLY when this
-- instance's whole-instance retirement row was closed 'rolled-back' IN THIS TRANSACTION. "In this transaction" is
-- completed_at = transaction_timestamp(): the service closes the row with now() (the same value) immediately before it
-- moves the lifecycle, so a row closed rolled-back by an EARLIER attempt cannot license a later flip (that attempt's
-- completed_at is older than this transaction's start). It cannot be forged by an update, because the closing
-- columns are set once, nor by an INSERT, because a whole-instance row cannot be inserted already closed (the stamp trigger). retiring -> retired additionally requires that no live placement remains: completeRetire retires
-- them first, in the same transaction, and a retired instance must hold no container name, directory or port (V007's
-- partial unique indexes, V016's port triggers). A same-value write is not a transition: the trigger's WHEN filters it.
-- An unknown value passes through so instance_lifecycle (the CHECK) refuses it by name.
CREATE OR REPLACE FUNCTION sm_data.instance_lifecycle_moves_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle = 'pending' AND NEW.lifecycle IN ('active', 'abandoned') THEN
        RETURN NEW;
    END IF;
    IF OLD.lifecycle = 'active' AND NEW.lifecycle = 'retiring' THEN
        RETURN NEW;
    END IF;
    IF OLD.lifecycle = 'retiring' AND NEW.lifecycle = 'retired' THEN
        IF EXISTS (SELECT 1 FROM sm_data.placement
                   WHERE tenant_id = OLD.tenant_id AND instance_id = OLD.instance_id AND retired_at IS NULL) THEN
            RAISE EXCEPTION 'an instance lifecycle may reach retired only after its placements are retired';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle = 'retiring' AND NEW.lifecycle = 'active' THEN
        IF EXISTS (SELECT 1 FROM sm_data.instance_retirement r
                   WHERE r.tenant_id = OLD.tenant_id AND r.instance_id = OLD.instance_id AND r.placement_id IS NULL
                     AND r.outcome = 'rolled-back' AND r.completed_at = transaction_timestamp()) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'an instance lifecycle may return from retiring to active only with its retirement closed rolled-back in the same transaction';
    END IF;
    IF NEW.lifecycle NOT IN ('pending', 'active', 'abandoned', 'retiring', 'retired') THEN
        RETURN NEW;                                  -- not a state at all: instance_lifecycle (the CHECK) refuses it, by name
    END IF;
    RAISE EXCEPTION 'an instance lifecycle may only move pending to active or abandoned, active to retiring, retiring to retired, or retiring back to active after a rolled-back retirement';
END $$;

-- LOCK CLASS: ALTER/ADD CONSTRAINT and DROP INDEX take ACCESS EXCLUSIVE on sm_data.instance_state; CREATE UNIQUE INDEX takes
-- SHARE; everything else creates objects nothing references yet. instance_state holds one row per declared instance, so this is
-- a short maintenance-window migration. The new binary does not go Ready on a schema older than this migration (RetirementPersistenceConfiguration's readiness stage).
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand, in this order, and
-- only while no instance is 'retiring' or 'retired' (the CHECK and the index cannot be narrowed over such a row): DROP TRIGGER
-- instance_retirement_guard and instance_retirement_stamp ON sm_data.instance_retirement; DROP TABLE sm_data.instance_retirement;
-- DROP FUNCTION sm_data.instance_retirement_closed_once() and sm_data.stamp_instance_retirement_state_version(); restore V007's
-- instance_lifecycle_moves_forward() body; DROP INDEX sm_data.instance_name_once and re-create it WHERE lifecycle <> 'abandoned';
-- re-create the instance_lifecycle CHECK over ('pending', 'active', 'abandoned'); finally delete the V023 row from
-- flyway_schema_history.
