-- Linked deployment targets are represented by deployment_target_runtime plus the parent
-- connection_runtime projection.  The original placement foreign key predates that model
-- and accepted only legacy target_connection rows, which made a valid linked target fail
-- during instance declaration with placement_tenant_id_target_id_fkey.

ALTER TABLE sm_data.placement
    DROP CONSTRAINT IF EXISTS placement_tenant_id_target_id_fkey;

-- A placement target is valid when it is either a legacy target_connection row or a live
-- linked deployment_target_runtime row whose parent Connection projection is live.  This
-- trigger is the cross-table equivalent of the former FK; it keeps the browser from ever
-- supplying SSH data and preserves the target_id on the immutable placement.
CREATE OR REPLACE FUNCTION sm_data.placement_inside_target_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    t sm_data.target_connection%ROWTYPE;
    linked record;
    v_root text;
    v_prefix text;
    v_runtime text;
    v_pattern text;
BEGIN
    IF NEW.connection_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO t FROM sm_data.target_connection
        WHERE tenant_id = NEW.tenant_id AND target_id = NEW.target_id;
    IF FOUND THEN
        IF t.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'placement target_id names a retired target'
                USING ERRCODE = 'check_violation', COLUMN = 'target_id';
        END IF;
        v_root := t.allowed_config_root;
        v_prefix := t.allowed_container_prefix;
        v_runtime := 'docker';
    ELSE
        SELECT d.runtime, d.settings, c.retired_at AS connection_retired_at
            INTO linked
            FROM sm_data.deployment_target_runtime d
            JOIN sm_data.connection_runtime c
              ON c.tenant_id = d.tenant_id AND c.connection_id = d.connection_id
             AND c.retired_at IS NULL
            WHERE d.tenant_id = NEW.tenant_id AND d.target_id = NEW.target_id
              AND d.retired_at IS NULL;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'placement target_id names an unknown target'
                USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'placement_tenant_id_target_id_fkey';
        END IF;
        IF linked.connection_retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'placement target_id names a retired target connection'
                USING ERRCODE = 'check_violation', COLUMN = 'target_id';
        END IF;
        v_root := linked.settings ->> 'configRoot';
        v_prefix := linked.settings ->> 'containerPrefix';
        v_runtime := linked.runtime;
        v_pattern := linked.settings ->> 'serviceNamePattern';
        IF v_root IS NULL OR v_root = '' THEN
            RAISE EXCEPTION 'linked deployment target has no config root'
                USING ERRCODE = 'check_violation', COLUMN = 'target_id';
        END IF;
    END IF;

    IF v_runtime = 'docker' THEN
        IF v_prefix IS NULL OR v_prefix = '' OR NOT starts_with(NEW.container_name, v_prefix) THEN
            RAISE EXCEPTION 'placement container_name is outside the target''s allowed container prefix'
                USING ERRCODE = 'check_violation', COLUMN = 'container_name';
        END IF;
    ELSIF v_runtime = 'systemd' THEN
        IF v_pattern IS NULL OR v_pattern = ''
                OR (CASE WHEN right(v_pattern, 1) = '-' THEN NOT starts_with(NEW.container_name, v_pattern)
                         ELSE NEW.container_name !~ v_pattern END) THEN
            RAISE EXCEPTION 'placement container_name is outside the target''s allowed service pattern'
                USING ERRCODE = 'check_violation', COLUMN = 'container_name';
        END IF;
    ELSE
        RAISE EXCEPTION 'placement target runtime is unsupported'
            USING ERRCODE = 'check_violation', COLUMN = 'target_id';
    END IF;

    IF NOT (NEW.remote_config_directory = v_root
            OR starts_with(NEW.remote_config_directory, v_root || '/')) THEN
        RAISE EXCEPTION 'placement remote_config_directory is outside the target''s allowed config root'
            USING ERRCODE = 'check_violation', COLUMN = 'remote_config_directory';
    END IF;
    RETURN NEW;
END $$;
