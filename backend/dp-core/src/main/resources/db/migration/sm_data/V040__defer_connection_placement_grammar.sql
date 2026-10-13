-- Plan 015 interim behavior: placement container/path/file grammar is retained for legacy target-backed rows,
-- but deferred for connection-backed instance creation until runtime-specific placement validation is introduced.
-- The connection runtime projection and its liveness are still required by V039's foreign key and the service.

ALTER TABLE sm_data.placement
    DROP CONSTRAINT placement_container_name,
    DROP CONSTRAINT placement_config_directory,
    DROP CONSTRAINT placement_container_config_path,
    DROP CONSTRAINT placement_file_name;

ALTER TABLE sm_data.placement
    ADD CONSTRAINT placement_container_name CHECK (
        connection_id IS NOT NULL OR container_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    ADD CONSTRAINT placement_config_directory CHECK (
        connection_id IS NOT NULL OR (
            remote_config_directory ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$'
            AND strpos(remote_config_directory, '..') = 0)),
    ADD CONSTRAINT placement_container_config_path CHECK (
        connection_id IS NOT NULL OR (
            container_config_path ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$'
            AND strpos(container_config_path, '..') = 0)),
    ADD CONSTRAINT placement_file_name CHECK (
        connection_id IS NOT NULL OR file_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$');

CREATE OR REPLACE FUNCTION sm_data.placement_inside_target_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    t sm_data.target_connection%ROWTYPE;
BEGIN
    -- Connection-backed placement validation is intentionally deferred. The live projection remains enforced by the
    -- placement_connection_has_runtime foreign key and InventoryService.holdConnectionRuntimeLive().
    IF NEW.connection_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO t FROM sm_data.target_connection
        WHERE tenant_id = NEW.tenant_id AND target_id = NEW.target_id;
    IF NOT FOUND THEN
        RETURN NEW;                                  -- the foreign key refuses an unknown target, by name
    END IF;
    IF t.retired_at IS NOT NULL THEN
        RAISE EXCEPTION 'placement target_id names a retired target'
            USING ERRCODE = 'check_violation', COLUMN = 'target_id';
    END IF;
    IF NOT starts_with(NEW.container_name, t.allowed_container_prefix) THEN
        RAISE EXCEPTION 'placement container_name is outside the target''s allowed container prefix'
            USING ERRCODE = 'check_violation', COLUMN = 'container_name';
    END IF;
    IF NOT (NEW.remote_config_directory = t.allowed_config_root
            OR starts_with(NEW.remote_config_directory, t.allowed_config_root || '/')) THEN
        RAISE EXCEPTION 'placement remote_config_directory is outside the target''s allowed config root'
            USING ERRCODE = 'check_violation', COLUMN = 'remote_config_directory';
    END IF;
    RETURN NEW;
END $$;
