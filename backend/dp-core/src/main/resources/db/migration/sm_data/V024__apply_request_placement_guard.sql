-- PLAN 008 S4, BE-D48 (owner ruling OR-121, OQ-8.5 (a); design.md section 8 row 13's second half). Run by Flyway as
-- sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so the column and the constraints below are born owned by
-- sm_dp_owner. Keyed by tenant_id first (I5); no foreign key leaves sm_data (I7); no secret is stored (I6).
--
-- WHAT. An apply_request can name only a placement that the registry holds, for THAT instance of THAT tenant. Until now
-- apply_request.placement_id carried no referential guard (V002's own comment): the service refused an unregistered
-- placement before admission (requireLivePlacement) and the catalog refused it at execution (TablePlacementCatalog), but a
-- new submit path that forgot the first could write a request for a placement of another instance, and the journal would
-- keep it. This migration is the second enforcer, in the database; the service and the catalog stay the first and the third.
--
-- HOW. The nil UUID is the explicit instance-wide sentinel (a stop only) and names no placement, so a plain foreign key on
-- placement_id would refuse every instance-wide stop. A STORED generated column placement_ref = NULLIF(placement_id, nil)
-- turns the sentinel into SQL NULL, and a MATCH SIMPLE foreign key (tenant_id, instance_id, placement_ref) skips a row
-- whose column is NULL ("if any of them are null, the row is not required to have a match": PostgreSQL 14, CREATE TABLE,
-- referential actions). Every other row must match a placement of the same tenant AND the same instance, which also
-- binds a placement to the request's instance in the database (the v2 paper's F20). The generated column is not in the
-- runtime role's INSERT grant (V002's column list) and cannot be written at all, so no writer steers the guard.
-- placement_id keeps its meaning and its fences (the three partial unique indexes still key on it).
--
-- THE FK IS EXISTENCE, NEVER LIVENESS. A retired placement (V007: retired_at, the only legal change to a placement row)
-- stays referenceable: an instance-wide stop's journal may still name it, and history must stay writable. A request for a
-- retired placement is admitted by the database and refused at pre-flight by the catalog (placement.unresolvable), as before.
-- Placements are never deleted (no DELETE grant), so the default NO ACTION cannot block a legitimate operation. ON UPDATE /
-- ON DELETE actions on a generated referencing column are not wanted and are not used.
--
-- THE PLACEMENT'S NEW KEY. A foreign key needs a unique target. placement's primary key is (tenant_id, placement_id);
-- UNIQUE (tenant_id, instance_id, placement_id) is strictly weaker than that key as a statement about placement_id (it
-- adds a column to a key that is already unique) and costs one small index.
--
-- EXISTING ROWS. V008 forced a recreate of the apply history and every later submit path checks a live placement, so
-- the expected number of rows that name an unregistered placement, or one of another instance, is 0 (plan 008 MF-4;
-- NOT measured on the owner's OR-118 database from a lane, so this migration refuses rather than assumes). If any row does,
-- the migration stops with a fixed sentence, rolls back, and changes nothing. It never prints a row (I6). The operator's own
-- check is the query in the sentence's runbook section: rows of apply_request with placement_id <> nil and no placement with
-- the same (tenant_id, instance_id, placement_id). The clean-up is the owner's decision (docs/design/backend/
-- agent-gateway/RECONCILIATION-008.md and plan 008 OQ-8.5): delete terminal demo-era rows, or re-register the missing
-- placement; a NOT VALID constraint is deliberately not offered, because an unvalidated guard is not the guard.
--
-- LOCK CLASS. LOCK TABLE ... ACCESS EXCLUSIVE on sm_data.apply_request first, held to the end of the migration's
-- transaction, so no request can be admitted between the check and the constraint (a SHARE lock would be enough for the check,
-- but ADD COLUMN takes ACCESS EXCLUSIVE regardless). ADD COLUMN ... GENERATED ... STORED REWRITES the table once (measured on
-- PostgreSQL 16 by pg_relation_filenode before and after: the filenode changes; PostgreSQL 14 is owed); the table is the
-- operator-intent history, small, and this is a maintenance-window migration, not a background one. ADD FOREIGN KEY then
-- takes SHARE ROW EXCLUSIVE on placement and scans apply_request once under the lock already held. ADD UNIQUE on placement
-- takes ACCESS EXCLUSIVE on placement (one row per placement).
--
-- THE SIDE EFFECT, MEASURED (on PostgreSQL 16 only; the 14 docs forbid the access but do not specify the value). A STORED
-- generated column is computed AFTER BEFORE triggers ("Generated columns are, conceptually, updated after BEFORE triggers have
-- run ... it is not allowed to access generated columns in BEFORE triggers": postgresql.org/docs/14/ddl-generated-columns.html).
-- Measured on 16: inside a BEFORE UPDATE trigger NEW.placement_ref is NULL while OLD.placement_ref is the placement. The
-- redefinition below is correct whichever value NEW carries (NULL or the stale OLD value), because placement_ref leaves the
-- comparison either way; what depends on the 16 measurement is the discriminating power of the two fold tests. Two published functions compare the WHOLE row,
-- to_jsonb(NEW) against to_jsonb(OLD), minus the columns an update may legally change: V006's forbid_terminal_regression
-- (the compaction fold of attempt_summary on a terminal request) and V008's stamp_apply_request_state_version (a fold or a
-- no-op keeps the stamp). Left alone, the first REFUSES every fold ("terminal and immutable") and the second draws a new
-- state_version, and takes the instance_state lock, on every UPDATE. Step 5 redefines both, bodies identical to V006 and V008
-- except that placement_ref also leaves the comparison. It is a function of placement_id, which the comparison still sees.
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand, in this order:
-- restore V006's and V008's function bodies (CREATE OR REPLACE with their original text); ALTER TABLE sm_data.apply_request DROP CONSTRAINT apply_request_placement_registered; ALTER TABLE sm_data.apply_request
-- DROP COLUMN placement_ref; ALTER TABLE sm_data.placement DROP CONSTRAINT placement_instance_identity_key; finally delete
-- the V024 row from flyway_schema_history. Nothing else depends on the guard.

LOCK TABLE sm_data.apply_request IN ACCESS EXCLUSIVE MODE;

-- 1. REFUSE what the guard would refuse, before changing anything. The sentence names the procedure and no value (I6).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sm_data.apply_request r
               WHERE r.placement_id <> '00000000-0000-0000-0000-000000000000'
                 AND NOT EXISTS (SELECT 1 FROM sm_data.placement p
                                 WHERE p.tenant_id = r.tenant_id AND p.instance_id = r.instance_id
                                   AND p.placement_id = r.placement_id)) THEN
        RAISE EXCEPTION 'V024 refuses to run while an apply_request names a placement that is not registered for its '
            'instance: re-register the placement or clear the demo-era row, then re-run (docs/runbooks/operations.md, '
            '"Upgrade across the apply-request placement guard")';
    END IF;
END $$;

-- 2. The new key on the referenced side, BEFORE the referencing side asks for it.
ALTER TABLE sm_data.placement ADD CONSTRAINT placement_instance_identity_key
    UNIQUE (tenant_id, instance_id, placement_id);

-- 3. The referencing column: the sentinel becomes NULL, every other value is the placement itself.
ALTER TABLE sm_data.apply_request ADD COLUMN placement_ref uuid
    GENERATED ALWAYS AS (NULLIF(placement_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;

-- 4. The guard. MATCH SIMPLE is the default, written out so a reader sees the NULL rule it relies on. VALIDATED: step 1
--    proved every existing row, and the table is locked until the transaction ends.
ALTER TABLE sm_data.apply_request ADD CONSTRAINT apply_request_placement_registered
    FOREIGN KEY (tenant_id, instance_id, placement_ref)
    REFERENCES sm_data.placement (tenant_id, instance_id, placement_id) MATCH SIMPLE;

-- 5. The two whole-row comparisons that a generated column would otherwise defeat (see THE SIDE EFFECT above). CREATE OR
--    REPLACE keeps each function's owner (sm_dp_owner) and its trigger; nothing else about either changes.
CREATE OR REPLACE FUNCTION sm_data.forbid_terminal_regression() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'STAGED' AND NEW.state = 'SUPERSEDED' THEN
        RETURN NEW;                            -- sanctioned transition 1: the supersession mark (V006)
    END IF;
    IF OLD.state NOT IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE') THEN
        IF NEW.state = OLD.state
           AND to_jsonb(NEW) - 'attempt_summary' - 'placement_ref' = to_jsonb(OLD) - 'attempt_summary' - 'placement_ref' THEN
            RETURN NEW;                        -- sanctioned write 2: §3.3's compaction fold (V006)
        END IF;
        RAISE EXCEPTION 'apply_request % is terminal (%) and immutable', OLD.apply_id, OLD.state;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION sm_data.stamp_apply_request_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'attempt_summary' - 'state_version' - 'placement_ref')
         = (to_jsonb(OLD) - 'attempt_summary' - 'state_version' - 'placement_ref') THEN
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
