-- Plan 014 step 2: a declared instance may be bound to a remote-server Connection instead of a legacy Target.
--
-- WHERE THE REFERENCE LIVES. A Target was never an instance attribute: target_id lives on the immutable placement row
-- (OR-29), and a re-target mints a new placement. The Connection is the same kind of host binding, so connection_id
-- lives beside target_id on sm_data.placement, and exactly one of the two is set on every row. instance_state gains
-- only deployment_mode, the per-instance deployment choice, stored like acquisition_mode (OR-71): chosen at declare,
-- immutable, in no UPDATE grant.
--
-- I7: a Connection is a control-plane object (sm_control.connection). There is NO foreign key across the plane
-- boundary. The foreign key below references sm_data.connection_runtime (V038), the DP-owned projection of that
-- Connection, so a connection-backed placement FAILS CLOSED while the projection is absent (plan 014: "a missing live
-- projection must fail closed ... rather than fall back to a Target or accept browser-supplied host data").
--
-- Legacy target-backed rows are untouched: they keep target_id, their foreign key, their per-target indexes and their
-- envelope check. Every existing instance reads deployment_mode = 'dp-remote', which is what they are.

-- ---- instance_state.deployment_mode -------------------------------------------------------------------------
-- The vocabulary is console.yaml's DeploymentMode. Only 'dp-remote' is accepted by the gateway today; 'host-local' and
-- 'federated' are reserved (plan 014) and present here so the column does not need a migration to widen it later.
ALTER TABLE sm_data.instance_state
    ADD COLUMN deployment_mode text NOT NULL DEFAULT 'dp-remote'
        CONSTRAINT instance_deployment_mode CHECK (deployment_mode IN ('dp-remote', 'host-local', 'federated'));
GRANT INSERT (deployment_mode) ON sm_data.instance_state TO sm_dp_runtime;

-- ---- placement.connection_id --------------------------------------------------------------------------------
ALTER TABLE sm_data.placement ALTER COLUMN target_id DROP NOT NULL;
ALTER TABLE sm_data.placement ADD COLUMN connection_id uuid NULL;
ALTER TABLE sm_data.placement ADD CONSTRAINT placement_one_host_binding
    CHECK ((target_id IS NULL) <> (connection_id IS NULL));
ALTER TABLE sm_data.placement ADD CONSTRAINT placement_connection_has_runtime
    FOREIGN KEY (tenant_id, connection_id) REFERENCES sm_data.connection_runtime (tenant_id, connection_id);
-- (The table-level INSERT grant of V007 already covers the new column; UPDATE stays retired_at only.)

-- OR-29's three uniqueness rules, per CONNECTION, among live placements. The V007 indexes are keyed by target_id and a
-- NULL target_id never collides, so connection-backed rows need their own. ⚠️ Per connection, not per host: two
-- Connections naming one host do not collide here (connection_runtime has no host-once index, unlike
-- target_connection_host_once). Routed to plan 014 step 3, which resolves the host through the connection.
CREATE UNIQUE INDEX placement_connection_container_once
    ON sm_data.placement (tenant_id, connection_id, container_name)
    WHERE retired_at IS NULL AND connection_id IS NOT NULL;
CREATE UNIQUE INDEX placement_connection_config_dir_once
    ON sm_data.placement (tenant_id, connection_id, remote_config_directory)
    WHERE retired_at IS NULL AND connection_id IS NOT NULL;
CREATE UNIQUE INDEX placement_connection_health_port_once
    ON sm_data.placement (tenant_id, connection_id, health_port)
    WHERE retired_at IS NULL AND connection_id IS NOT NULL;

-- B2, the second enforcer, extended to the connection's envelope. V007's body is kept verbatim for a target-backed row;
-- a connection-backed row reads the live connection_runtime projection the same way. Same tenant, same schema.
CREATE OR REPLACE FUNCTION sm_data.placement_inside_target_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    t sm_data.target_connection%ROWTYPE;
    c sm_data.connection_runtime%ROWTYPE;
    v_root text;
    v_prefix text;
BEGIN
    IF NEW.target_id IS NOT NULL THEN
        SELECT * INTO t FROM sm_data.target_connection
            WHERE tenant_id = NEW.tenant_id AND target_id = NEW.target_id;
        IF NOT FOUND THEN
            RETURN NEW;                              -- the foreign key refuses an unknown target, by name
        END IF;
        IF t.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'placement target_id names a retired target'
                USING ERRCODE = 'check_violation', COLUMN = 'target_id';
        END IF;
        v_root := t.allowed_config_root;
        v_prefix := t.allowed_container_prefix;
    ELSIF NEW.connection_id IS NOT NULL THEN
        SELECT * INTO c FROM sm_data.connection_runtime
            WHERE tenant_id = NEW.tenant_id AND connection_id = NEW.connection_id;
        IF NOT FOUND THEN
            RETURN NEW;                              -- placement_connection_has_runtime refuses it, by name
        END IF;
        IF c.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'placement connection_id names a retired connection'
                USING ERRCODE = 'check_violation', COLUMN = 'connection_id';
        END IF;
        v_root := c.allowed_config_root;
        v_prefix := c.allowed_container_prefix;
    ELSE
        RETURN NEW;                                  -- placement_one_host_binding refuses it, by name
    END IF;
    IF NOT starts_with(NEW.container_name, v_prefix) THEN
        RAISE EXCEPTION 'placement container_name is outside the target''s allowed container prefix'
            USING ERRCODE = 'check_violation', COLUMN = 'container_name';
    END IF;
    IF NOT (NEW.remote_config_directory = v_root
            OR starts_with(NEW.remote_config_directory, v_root || '/')) THEN
        RAISE EXCEPTION 'placement remote_config_directory is outside the target''s allowed config root'
            USING ERRCODE = 'check_violation', COLUMN = 'remote_config_directory';
    END IF;
    RETURN NEW;
END $$;

-- The converse, as V007 has it for targets: a connection projection is not retired while a live placement still sits
-- on it (plan 014: "A connection cannot be deleted while referenced by an active placement"). Names no row.
CREATE FUNCTION sm_data.connection_runtime_not_retired_under_live_placements() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.retired_at IS NULL AND NEW.retired_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM sm_data.placement
            WHERE tenant_id = OLD.tenant_id AND connection_id = OLD.connection_id AND retired_at IS NULL) THEN
        RAISE EXCEPTION 'a connection cannot be retired while it has live placements';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER connection_runtime_retire_guard BEFORE UPDATE ON sm_data.connection_runtime
    FOR EACH ROW EXECUTE FUNCTION sm_data.connection_runtime_not_retired_under_live_placements();
