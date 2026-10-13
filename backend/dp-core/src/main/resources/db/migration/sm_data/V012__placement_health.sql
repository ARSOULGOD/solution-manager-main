-- PLAN 010 T7.1 + T7.2 (sm_data V012). Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every
-- object below is born owned by sm_dp_owner. Keyed by tenant_id first (I5); no foreign key leaves sm_data (I7).
-- Section 1 (T7.1) closes OR-89's residual; section 2 (T7.2, placement_health) is appended by that task's commit.

-- ==== 1. every host key a target's host offers - OR-89's residual (T7.1) ========================================
-- V007's target_connection_host_once makes the PINNED host key unique per organization among live targets. A host that
-- offers several key types (ed25519, ecdsa, rsa) has one fingerprint per type, so the same machine could still be
-- registered twice in one organization by pinning two different types. The registration command therefore records
-- EVERY fingerprint the host presents (it connects once per key type, or takes the operator's explicit list) and this
-- table makes each of them unique per organization among live targets - whichever of them a later registration pins.
-- The pinned fingerprint itself is mirrored in by the triggers below, so the invariant holds for every writer.
CREATE TABLE sm_data.target_host_key (
    tenant_id uuid NOT NULL,
    target_id uuid NOT NULL,
    fingerprint text NOT NULL
        CONSTRAINT target_host_key_fingerprint CHECK (fingerprint ~ '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$'),
    -- mirrors target_connection.retired_at (trigger below): a retired target frees its keys.
    retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, target_id, fingerprint),
    CONSTRAINT target_host_key_has_target FOREIGN KEY (tenant_id, target_id)
        REFERENCES sm_data.target_connection (tenant_id, target_id));
CREATE UNIQUE INDEX target_host_key_once ON sm_data.target_host_key (tenant_id, fingerprint)
    WHERE retired_at IS NULL;

-- Existing targets: their pinned key (pre-production: there is no customer database, and V007's own unique index
-- guarantees the live ones are distinct).
INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint, retired_at)
    SELECT tenant_id, target_id, host_key_fingerprint, retired_at FROM sm_data.target_connection;

-- The pinned key is always among the target's keys, whoever inserts or re-pins the target. The conflict target is
-- NAMED: a bare ON CONFLICT DO NOTHING would also swallow the (tenant, fingerprint) uniqueness this table exists for.
CREATE FUNCTION sm_data.target_pinned_key_recorded() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint, retired_at)
        VALUES (NEW.tenant_id, NEW.target_id, NEW.host_key_fingerprint, NEW.retired_at)
        ON CONFLICT (tenant_id, target_id, fingerprint) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER target_connection_pinned_key_insert AFTER INSERT ON sm_data.target_connection
    FOR EACH ROW EXECUTE FUNCTION sm_data.target_pinned_key_recorded();
CREATE TRIGGER target_connection_pinned_key_update AFTER UPDATE OF host_key_fingerprint ON sm_data.target_connection
    FOR EACH ROW WHEN (OLD.host_key_fingerprint IS DISTINCT FROM NEW.host_key_fingerprint)
    EXECUTE FUNCTION sm_data.target_pinned_key_recorded();

-- Retiring a target retires its keys (the only change retirement allows), which frees them for a fresh registration.
CREATE FUNCTION sm_data.target_keys_follow_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sm_data.target_host_key SET retired_at = NEW.retired_at
        WHERE tenant_id = NEW.tenant_id AND target_id = NEW.target_id AND retired_at IS NULL;
    RETURN NEW;
END $$;
CREATE TRIGGER target_connection_keys_retire AFTER UPDATE OF retired_at ON sm_data.target_connection
    FOR EACH ROW WHEN (OLD.retired_at IS NULL AND NEW.retired_at IS NOT NULL)
    EXECUTE FUNCTION sm_data.target_keys_follow_retirement();

-- Grants: nothing is deletable; the only update is retirement (which the trigger above performs as the invoking role).
GRANT SELECT, INSERT ON sm_data.target_host_key TO sm_dp_runtime;
GRANT UPDATE (retired_at) ON sm_data.target_host_key TO sm_dp_runtime;

-- ==== 2. placement_health - the DP's polled health, persisted on change (T7.2, OR-39) ===========================
-- One row per placement: the last verdict, when it was last CHECKED, when it last CHANGED, and the OR-35 stamp that
-- moves only when the state does. "Only changes are persisted" is therefore not a convention of the poller but a
-- property of this table: a probe that confirms the same state moves checked_at and nothing else, whoever writes it.
-- The state is the notify vocabulary (contracts/events/notify-events.schema.json's instance.health payload, which
-- console.yaml's Health enum repeats): the five rollup keys and the four NOTFOUND sub-states, kept as distinct values.
-- A placement is immutable and never deleted, so its health row is kept when it is retired; reads join to the live set.
CREATE TABLE sm_data.placement_health (
    tenant_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    state text NOT NULL
        CONSTRAINT placement_health_state CHECK (state IN ('GREEN', 'RED', 'RESTART_EXPECTED', 'INACTIVE_REPLICA',
            'NOT_APPLICABLE', 'unreachable', 'not_exposed', 'missing', 'bootstrap')),
    checked_at timestamptz NOT NULL,
    changed_at timestamptz NOT NULL,
    state_version bigint NOT NULL,
    PRIMARY KEY (tenant_id, placement_id),
    CONSTRAINT placement_health_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id),
    CONSTRAINT placement_health_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id));

-- The stamp. ONE FUNCTION PER OWNING TABLE (V003's measured reason). An INSERT, and an UPDATE that CHANGES the state,
-- take the owning instance_state row FOR UPDATE (the recorded commit-order rule: within one instance, stamp order is
-- commit order) and then the next value of the one sequence. An UPDATE that leaves the state alone takes no lock, draws
-- no value, keeps the old stamp AND keeps changed_at: a confirmation moves checked_at only. An INSERT must name the
-- instance the placement really belongs to (the foreign keys alone would accept any declared instance).
CREATE FUNCTION sm_data.stamp_placement_health_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    owner uuid;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.state IS NOT DISTINCT FROM OLD.state THEN
        NEW.state_version := OLD.state_version;
        NEW.changed_at := OLD.changed_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT instance_id INTO owner FROM sm_data.placement
            WHERE tenant_id = NEW.tenant_id AND placement_id = NEW.placement_id;
        IF owner IS NOT NULL AND owner <> NEW.instance_id THEN
            RAISE EXCEPTION 'placement_health names an instance the placement does not belong to'
                USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'placement_health_has_instance';
        END IF;
    END IF;
    PERFORM 1 FROM sm_data.instance_state
        WHERE tenant_id = NEW.tenant_id AND instance_id = NEW.instance_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'placement_health names an instance the data plane has not declared'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'placement_health_has_instance';
    END IF;
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER placement_health_stamp BEFORE INSERT OR UPDATE ON sm_data.placement_health
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_placement_health_state_version();

-- Grants: INSERT is column-scoped to everything but state_version (the trigger stamps it; an explicit value is refused
-- 42501, measured in V007); UPDATE reaches the three columns a probe moves, not identity and not the stamp. No DELETE.
GRANT SELECT ON sm_data.placement_health TO sm_dp_runtime;
GRANT INSERT (tenant_id, placement_id, instance_id, state, checked_at, changed_at)
    ON sm_data.placement_health TO sm_dp_runtime;
GRANT UPDATE (state, checked_at, changed_at) ON sm_data.placement_health TO sm_dp_runtime;
