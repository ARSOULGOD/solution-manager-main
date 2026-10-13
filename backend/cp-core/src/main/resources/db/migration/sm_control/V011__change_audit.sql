-- OR-19: an append-only, trigger-written audit of every sm_control state change. Never edit this
-- file once applied (OR-22) — a correction is a new migration, never an edit here.
--
-- Why a trigger and not application code: a trigger fires for EVERY INSERT/UPDATE/DELETE on the
-- tables below regardless of which code path performed it, so no future write path can silently
-- skip the audit trail. The application supplies CONTEXT (actor, local operation, correlation id,
-- client address) through transaction-local settings (see cp-core's AuditContext /
-- JdbcAuditContext), which this trigger reads and tolerates as absent — a missing context never
-- blocks the business write.
-- m7: a MALFORMED context value (an application bug, not an absent one) must never block the
-- business write the trigger below protects — the ::uuid cast on a bad string raises
-- invalid_text_representation, which without this guard propagates out of the trigger and fails the
-- INSERT/UPDATE/DELETE it was only ever supposed to observe. NULLIF(..., '') already tolerates
-- ABSENT context (current_setting returns ''); this tolerates MALFORMED context the same way,
-- treating an unparseable value as if it had never been set at all.
CREATE FUNCTION sm_control.try_uuid(v text) RETURNS uuid
    LANGUAGE plpgsql
    IMMUTABLE
    AS $$
BEGIN
    RETURN v::uuid;
EXCEPTION WHEN invalid_text_representation THEN
    RETURN NULL;
END;
$$;

CREATE TABLE sm_control.change_audit (
    change_audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recorded_at timestamptz NOT NULL,
    txid bigint NOT NULL,
    table_name text NOT NULL,
    operation text NOT NULL CONSTRAINT change_audit_operation CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
    row_key jsonb NOT NULL,
    changed_columns text[] NOT NULL DEFAULT ARRAY[]::text[],
    old_values jsonb,
    new_values jsonb,
    secret_columns_changed text[] NOT NULL DEFAULT ARRAY[]::text[],
    actor_principal_id uuid,
    audit_operation text CONSTRAINT change_audit_audit_operation CHECK (
        audit_operation IS NULL OR audit_operation IN (
            'LOGIN_SUCCEEDED', 'LOGIN_REFUSED', 'LOGOUT', 'CREDENTIAL_ROTATED',
            'CREDENTIAL_ROTATION_REFUSED', 'LOCKOUT_CLEARED')),
    correlation_id uuid,
    client_address text,
    db_role text NOT NULL,
    CONSTRAINT change_audit_actor_principal_fk FOREIGN KEY (actor_principal_id)
        REFERENCES sm_control.principal (id) ON DELETE RESTRICT
);

CREATE INDEX change_audit_table_name_recorded_at_idx ON sm_control.change_audit (table_name, recorded_at);
CREATE INDEX change_audit_correlation_id_idx ON sm_control.change_audit (correlation_id) WHERE correlation_id IS NOT NULL;

-- SECURITY DEFINER: sm_cp_runtime holds no INSERT on change_audit (see the grants at the bottom of
-- this file), yet its ordinary business writes on the tables below must still produce an audit row.
-- Running as the function OWNER (sm_cp_owner — this migration creates it under SmControlMigrator's
-- `SET ROLE sm_cp_owner`, so ownership is automatic) is what makes that possible. `search_path` is
-- pinned so an unqualified reference cannot be redirected by a caller's own search_path.
CREATE FUNCTION sm_control.record_change_audit() RETURNS trigger
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
    -- is bookkeeping, not an action, and writes no row.
    IF TG_TABLE_NAME = 'session' AND TG_OP = 'UPDATE'
       AND (v_new_json - ARRAY['last_used_at', 'idle_expires_at']::text[])
         = (v_old_json - ARRAY['last_used_at', 'idle_expires_at']::text[]) THEN
        RETURN NEW;
    END IF;

    -- Context the application supplied through transaction-local settings, immediately before the
    -- writes it should attribute. Absent or blank is tolerated as NULL.
    v_actor := sm_control.try_uuid(NULLIF(current_setting('sm.audit.actor', true), ''));
    v_operation := NULLIF(current_setting('sm.audit.operation', true), '');
    v_correlation := sm_control.try_uuid(NULLIF(current_setting('sm.audit.correlation_id', true), ''));
    v_client := NULLIF(current_setting('sm.audit.client_address', true), '');

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

CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.organization
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.organization_unit
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.environment
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.solution
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.instance
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.scope
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.principal
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.permission_catalog
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.role
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.role_permission
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.role_binding
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.session
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.login_throttle
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();

-- Immutability as an artifact, not a convention: sm_cp_owner (the table owner) always bypasses
-- GRANT/REVOKE, so the REVOKE below is not sufficient on its own. A trigger fires regardless of the
-- caller's privilege level, so this closes the owner path too.
--
-- Fix 4: this ONE function backs two DIFFERENT rules — change_audit's own immutability
-- (change_audit_immutable_update/delete/truncate, below) and the OTHER 13 tables' truncate_refused
-- guard (further below). A caller refused by either must be told WHICH table and WHICH rule fired,
-- never a message that names change_audit even when the refused table is, say, session.
CREATE FUNCTION sm_control.reject_change_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_TABLE_NAME = 'change_audit' THEN
        RAISE EXCEPTION 'sm_control.change_audit rows are immutable (OR-19): % on change_audit is not permitted', TG_OP;
    ELSE
        RAISE EXCEPTION 'sm_control.% may not be truncated (OR-19): truncating control-plane state is never permitted', TG_TABLE_NAME;
    END IF;
END;
$$;

CREATE TRIGGER change_audit_immutable_update BEFORE UPDATE ON sm_control.change_audit
    FOR EACH ROW EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER change_audit_immutable_delete BEFORE DELETE ON sm_control.change_audit
    FOR EACH ROW EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER change_audit_immutable_truncate BEFORE TRUNCATE ON sm_control.change_audit
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();

-- M2: TRUNCATE never fires a row-level trigger — record_change_audit() above sees every INSERT,
-- UPDATE and DELETE on these 13 tables, but a TRUNCATE would erase every row in one statement with
-- NO audit trail at all. Truncating control-plane state is never a legitimate operation, so refusing
-- it outright is stronger (and simpler) than trying to audit it: reuse the same unconditional-raise
-- function change_audit's own immutability triggers use above.
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.organization
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.organization_unit
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.environment
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.solution
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.instance
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.scope
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.principal
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.permission_catalog
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.role
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.role_permission
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.role_binding
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.session
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.login_throttle
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();

-- sm_cp_runtime gets SELECT only. Rows are written solely by record_change_audit(), which runs
-- SECURITY DEFINER as the function owner, so sm_cp_runtime needs no INSERT of its own — and the
-- immutable triggers above refuse UPDATE/DELETE/TRUNCATE even if it were ever granted one.
REVOKE ALL PRIVILEGES ON TABLE sm_control.change_audit FROM sm_cp_runtime;
GRANT SELECT ON TABLE sm_control.change_audit TO sm_cp_runtime;
