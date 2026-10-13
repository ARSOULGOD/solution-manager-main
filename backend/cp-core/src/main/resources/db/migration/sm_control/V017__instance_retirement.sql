-- Plan 012 T4 (OR-121; OPS-Q2 U-C, OPS-Q4 (a)): the control plane's half of undeclare - an instance's governance node is
-- RETIRED, never deleted. Never edit this file once applied (OR-22): a correction is a new migration, never an edit here.
--
-- What "retired" does (the readers are in cp-core, tested at this version):
--   * the quota slot is freed: countInstances no longer counts it;
--   * the node is gone from every resolution: ResourceScopeDirectory answers "not found" for the instance id, so every
--     instance-scoped operation and every dashboard of it is a 404;
--   * a binding whose scope IS the retired instance grants nothing (OPS-Q4 (a), JdbcAuthorizationGrantRepository), so a
--     principal bound only there sees neither its environment nor its solution.
-- It never touches the workload (BE-D49) - nothing in this migration or in its callers reaches a host.
--
-- Why a column and not a delete: the instance row is referenced by dashboards (V014) and keys the audit trail's
-- row_key; a delete would orphan both, and "the control plane never deletes a governance node" is the standing rule.
-- A retired row is TERMINAL (instance_retired_is_terminal below): re-declare is out of scope (plan 012 section "Out").
--
-- Privileges: sm_cp_runtime gains UPDATE on exactly retired_at and retired_by. It holds no DELETE and no other UPDATE.
--
-- record_change_audit() is replaced below. The function is V014's body verbatim with exactly ONE edit: the OR-25
-- allow-list gains INSTANCE_UNDECLARED (and the audit_operation CHECK gains it, so the vocabulary stays closed on both
-- sides). Every earlier entry is kept: a dropped entry would make sm_cp_runtime's earlier writes fail OR-25 as unknown.

ALTER TABLE sm_control.instance
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retired_by uuid REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    ADD CONSTRAINT instance_retired_together CHECK ((retired_at IS NULL) = (retired_by IS NULL));

-- Retirement is terminal and set once: once retired_at is set no column of the row may change again, whoever asks
-- (a trigger fires for the owner too, unlike a privilege).
CREATE FUNCTION sm_control.instance_retired_is_terminal() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'instance % is retired: undeclare is terminal and a retired instance row cannot change',
        OLD.instance_id
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER instance_retired_is_terminal BEFORE UPDATE ON sm_control.instance
    FOR EACH ROW WHEN (OLD.retired_at IS NOT NULL)
    EXECUTE FUNCTION sm_control.instance_retired_is_terminal();

GRANT UPDATE (retired_at, retired_by) ON TABLE sm_control.instance TO sm_cp_runtime;

CREATE OR REPLACE FUNCTION sm_control.record_change_audit() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = sm_control, pg_temp
    AS $$
DECLARE
    v_secret_cols text[] := ARRAY['credential_hash', 'token_hash'];
    v_actor uuid;
    v_operation text;
    v_correlation uuid;
    v_client text;
    v_row_key jsonb;
    v_old jsonb;
    v_new jsonb;
    v_changed text[];
    v_secret_changed text[];
    -- The FULL row as jsonb, computed exactly once per fire and guarded by TG_OP: to_jsonb(anyelement)
    -- is a generic, runtime-typed cast that never needs to know a table's columns ahead of time, unlike
    -- `NEW.<field>`/`OLD.<field>` attribute syntax below — Postgres resolves every attribute reference
    -- in a SQL expression against the row's ACTUAL type at plan time, for every CASE branch, whether or
    -- not that branch is the one taken at runtime. A single shared function firing across 13 different
    -- row shapes cannot use per-table field names directly; every row_key/old/new value below is
    -- extracted from these two jsonb values with the `->>` TEXT-KEY operator instead, which never fails
    -- for a key a given table's row does not have (it simply returns NULL). NEW is unassigned for
    -- DELETE and OLD is unassigned for INSERT, so each is fetched only for the operations where it
    -- actually exists.
    v_new_json jsonb;
    v_old_json jsonb;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_new_json := NULL;
        v_old_json := to_jsonb(OLD);
    ELSIF TG_OP = 'INSERT' THEN
        v_new_json := to_jsonb(NEW);
        v_old_json := NULL;
    ELSE
        v_new_json := to_jsonb(NEW);
        v_old_json := to_jsonb(OLD);
    END IF;

    -- OR-19's own exclusion: a session touch that changes only the idle-expiry bookkeeping columns
    -- is bookkeeping, not an action, and writes no row. OR-25 does not change this: it must still
    -- return BEFORE any context check below, because a touch carries no context at all.
    IF TG_TABLE_NAME = 'session' AND TG_OP = 'UPDATE'
       AND (v_new_json - ARRAY['last_used_at', 'idle_expires_at']::text[])
         = (v_old_json - ARRAY['last_used_at', 'idle_expires_at']::text[]) THEN
        RETURN NEW;
    END IF;

    -- Context the application supplied through transaction-local settings, immediately before the
    -- writes it should attribute. Absent or blank is tolerated as NULL; a malformed uuid is too. For
    -- every role OTHER than sm_cp_runtime that tolerance is exactly as before — a write proceeds and
    -- is simply audited with NULL context. For sm_cp_runtime, the OR-25 block below treats that same
    -- "ended up NULL" outcome (whether the setting was absent or malformed) as a reason to refuse.
    v_actor := sm_control.try_uuid(NULLIF(current_setting('sm.audit.actor', true), ''));
    v_operation := NULLIF(current_setting('sm.audit.operation', true), '');
    v_correlation := sm_control.try_uuid(NULLIF(current_setting('sm.audit.correlation_id', true), ''));
    v_client := NULLIF(current_setting('sm.audit.client_address', true), '');

    -- OR-25: fail closed for the application's own runtime role. session_user is the LOGIN role and
    -- is immune to SET ROLE, unlike current_user — sm_cp_migrator's per-connection `SET ROLE
    -- sm_cp_owner` (SmControlMigrator.INIT_SQL) must never accidentally fall into this block.
    IF session_user = 'sm_cp_runtime' THEN
        IF v_operation IS NULL OR v_operation NOT IN (
                'LOGIN_SUCCEEDED', 'LOGIN_REFUSED', 'LOGOUT', 'CREDENTIAL_ROTATED',
                'CREDENTIAL_ROTATION_REFUSED', 'LOCKOUT_CLEARED',
                'INSTANCE_DECLARED', 'PACKAGE_LOADED', 'ENTITLEMENT_SET', 'TARGET_REGISTERED',
                'TARGET_RETIRED', 'DASHBOARD_WIDGET_SAVED', 'DASHBOARD_LAYOUT_SET',
                'DASHBOARD_WIDGET_HIDDEN', 'DASHBOARD_RESET', 'INSTANCE_UNDECLARED') THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: no known audit_operation',
                TG_TABLE_SCHEMA, TG_TABLE_NAME
                USING ERRCODE = 'OR025';
        END IF;
        IF v_correlation IS NULL THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: no well-formed correlation id',
                TG_TABLE_SCHEMA, TG_TABLE_NAME
                USING ERRCODE = 'OR025';
        END IF;
        IF v_client IS NULL THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: no client address',
                TG_TABLE_SCHEMA, TG_TABLE_NAME
                USING ERRCODE = 'OR025';
        END IF;
        IF v_actor IS NULL AND v_operation NOT IN ('LOGIN_REFUSED', 'LOCKOUT_CLEARED', 'ENTITLEMENT_SET') THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % requires an actor principal',
                TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                USING ERRCODE = 'OR025';
        END IF;
        -- (m3) The LOGIN_REFUSED/LOCKOUT_CLEARED actor exemption above is scoped to login_throttle
        -- ONLY: neither production path that uses either operation ever writes any OTHER audited
        -- table (LocalLoginService's refusal recording and SmControlLockoutRecoveryMain's clearing
        -- both write only login_throttle), so a NULL actor on a DIFFERENT table under one of these
        -- two operations is refused too, exactly as if the operation were not exempt at all.
        IF v_actor IS NULL AND v_operation IN ('LOGIN_REFUSED', 'LOCKOUT_CLEARED')
                AND TG_TABLE_NAME <> 'login_throttle' THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % with no actor is only permitted on login_throttle',
                TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                USING ERRCODE = 'OR025';
        END IF;
        -- Owner ruling 2026-09-29 (Q-E, OR-48): ENTITLEMENT_SET may carry no actor, scoped exactly as the
        -- exemption above is scoped to login_throttle — here to the entitlement table and nowhere else.
        -- The message keeps the 'requires an actor principal' wording: for every other table this IS the
        -- refusal an actor-less write to it has always been.
        IF v_actor IS NULL AND v_operation = 'ENTITLEMENT_SET' AND TG_TABLE_NAME <> 'entitlement' THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % requires an actor principal (a no-actor ENTITLEMENT_SET is only permitted on entitlement)',
                TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                USING ERRCODE = 'OR025';
        END IF;
    END IF;

    v_row_key := CASE TG_TABLE_NAME
        WHEN 'organization' THEN
            jsonb_build_object('organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'))
        WHEN 'organization_unit' THEN
            jsonb_build_object(
                'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                'organization_unit_id',
                    COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'))
        WHEN 'environment' THEN
            jsonb_build_object(
                'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                'organization_unit_id',
                    COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'),
                'environment_id', COALESCE(v_new_json ->> 'environment_id', v_old_json ->> 'environment_id'))
        WHEN 'solution' THEN
            jsonb_build_object(
                'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                'organization_unit_id',
                    COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'),
                'environment_id', COALESCE(v_new_json ->> 'environment_id', v_old_json ->> 'environment_id'),
                'solution_id', COALESCE(v_new_json ->> 'solution_id', v_old_json ->> 'solution_id'))
        WHEN 'instance' THEN
            jsonb_build_object(
                'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                'organization_unit_id',
                    COALESCE(v_new_json ->> 'organization_unit_id', v_old_json ->> 'organization_unit_id'),
                'environment_id', COALESCE(v_new_json ->> 'environment_id', v_old_json ->> 'environment_id'),
                'solution_id', COALESCE(v_new_json ->> 'solution_id', v_old_json ->> 'solution_id'),
                'instance_id', COALESCE(v_new_json ->> 'instance_id', v_old_json ->> 'instance_id'))
        WHEN 'scope' THEN
            jsonb_build_object('scope_id', COALESCE(v_new_json ->> 'scope_id', v_old_json ->> 'scope_id'))
        WHEN 'principal' THEN
            jsonb_build_object('id', COALESCE(v_new_json ->> 'id', v_old_json ->> 'id'))
        WHEN 'permission_catalog' THEN
            jsonb_build_object('permission_id', COALESCE(v_new_json ->> 'permission_id', v_old_json ->> 'permission_id'))
        WHEN 'role' THEN
            jsonb_build_object('role_id', COALESCE(v_new_json ->> 'role_id', v_old_json ->> 'role_id'))
        WHEN 'role_permission' THEN
            jsonb_build_object(
                'role_id', COALESCE(v_new_json ->> 'role_id', v_old_json ->> 'role_id'),
                'permission_id', COALESCE(v_new_json ->> 'permission_id', v_old_json ->> 'permission_id'))
        WHEN 'role_binding' THEN
            jsonb_build_object('binding_id', COALESCE(v_new_json ->> 'binding_id', v_old_json ->> 'binding_id'))
        WHEN 'session' THEN
            jsonb_build_object('session_id', COALESCE(v_new_json ->> 'session_id', v_old_json ->> 'session_id'))
        WHEN 'login_throttle' THEN
            jsonb_build_object('subject_hash', COALESCE(v_new_json ->> 'subject_hash', v_old_json ->> 'subject_hash'))
        WHEN 'catalog_solution' THEN
            jsonb_build_object('catalog_solution_id',
                COALESCE(v_new_json ->> 'catalog_solution_id', v_old_json ->> 'catalog_solution_id'))
        WHEN 'solution_package' THEN
            jsonb_build_object('load_seq', COALESCE(v_new_json ->> 'load_seq', v_old_json ->> 'load_seq'))
        WHEN 'entitlement' THEN
            jsonb_build_object(
                'organization_id', COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id'),
                'solution', COALESCE(v_new_json ->> 'solution', v_old_json ->> 'solution'))
        WHEN 'target' THEN
            jsonb_build_object('target_id', COALESCE(v_new_json ->> 'target_id', v_old_json ->> 'target_id'))
        WHEN 'dashboard' THEN
            jsonb_build_object('dashboard_id', COALESCE(v_new_json ->> 'dashboard_id', v_old_json ->> 'dashboard_id'))
        ELSE
            '{}'::jsonb
    END;

    IF TG_OP = 'INSERT' THEN
        v_old := NULL;
        v_new := v_new_json - v_secret_cols;
        SELECT array_agg(k ORDER BY k) INTO v_changed FROM jsonb_object_keys(v_new_json) AS k;
    ELSIF TG_OP = 'DELETE' THEN
        v_old := v_old_json - v_secret_cols;
        v_new := NULL;
        SELECT array_agg(k ORDER BY k) INTO v_changed FROM jsonb_object_keys(v_old_json) AS k;
    ELSE
        v_old := v_old_json - v_secret_cols;
        v_new := v_new_json - v_secret_cols;
        SELECT array_agg(n.key ORDER BY n.key) INTO v_changed
          FROM jsonb_each(v_new_json) AS n
          JOIN jsonb_each(v_old_json) AS o ON o.key = n.key
         WHERE n.value IS DISTINCT FROM o.value;
    END IF;

    -- A package's document is up to ~111 KB (filemesh-ftp-solace.package.json, measured): the audit row keeps the
    -- digest, which names the content exactly, and never copies the document into an immutable row per load.
    IF TG_TABLE_NAME = 'solution_package' THEN
        v_old := v_old - 'document';
        v_new := v_new - 'document';
    END IF;

    -- A dashboard's document is up to 64 KiB and is rewritten on every widget, layout, hide and reset: the audit row
    -- keeps the sha256 of the document's text, which names the content exactly, and never copies it per write.
    IF TG_TABLE_NAME = 'dashboard' THEN
        IF v_old IS NOT NULL THEN
            v_old := (v_old - 'document') || jsonb_build_object('document_sha256',
                encode(sha256(convert_to((v_old_json -> 'document')::text, 'UTF8')), 'hex'));
        END IF;
        IF v_new IS NOT NULL THEN
            v_new := (v_new - 'document') || jsonb_build_object('document_sha256',
                encode(sha256(convert_to((v_new_json -> 'document')::text, 'UTF8')), 'hex'));
        END IF;
    END IF;

    SELECT array_agg(c ORDER BY c) INTO v_secret_changed
      FROM unnest(COALESCE(v_changed, ARRAY[]::text[])) AS c
     WHERE c = ANY (v_secret_cols);

    INSERT INTO sm_control.change_audit (
        recorded_at, txid, table_name, operation, row_key, changed_columns,
        old_values, new_values, secret_columns_changed, actor_principal_id,
        audit_operation, correlation_id, client_address, db_role)
    VALUES (
        clock_timestamp(), txid_current(), TG_TABLE_NAME, TG_OP, v_row_key,
        COALESCE(v_changed, ARRAY[]::text[]), v_old, v_new,
        COALESCE(v_secret_changed, ARRAY[]::text[]), v_actor, v_operation, v_correlation, v_client,
        session_user::text);

    RETURN COALESCE(NEW, OLD);
END;
$$;

-- The audit_operation vocabulary is closed on both sides: the trigger's allow-list above and this CHECK.
ALTER TABLE sm_control.change_audit DROP CONSTRAINT change_audit_audit_operation;
ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_audit_operation CHECK (
    audit_operation IS NULL OR audit_operation IN (
        'LOGIN_SUCCEEDED', 'LOGIN_REFUSED', 'LOGOUT', 'CREDENTIAL_ROTATED',
        'CREDENTIAL_ROTATION_REFUSED', 'LOCKOUT_CLEARED',
        'INSTANCE_DECLARED', 'PACKAGE_LOADED', 'ENTITLEMENT_SET', 'TARGET_REGISTERED', 'TARGET_RETIRED',
        'DASHBOARD_WIDGET_SAVED', 'DASHBOARD_LAYOUT_SET', 'DASHBOARD_WIDGET_HIDDEN', 'DASHBOARD_RESET',
        'INSTANCE_UNDECLARED'));
