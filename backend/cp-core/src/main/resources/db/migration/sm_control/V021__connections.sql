-- Plan 013 T2 (BE-D66, OR-130): the Connection config object's control-plane row. Never edit this file once
-- applied (OR-22): a correction is a new migration, never an edit here.
--
-- Contents, in this order:
--   1. The permission catalog's four connection.* verbs (Permission.java, OR-130 section 1.10.1 rows 44-47) and
--      their role_permission rows: connection.view -> Viewer + Operator + Administrator; connection.create /
--      connection.edit / connection.delete -> Administrator-only. Catalog grows from 43 to 47.
--   2. sm_control.connection: the cp-owned row. Shaped like sm_control.target (V013) -- it carries its OWN
--      ownership_scope_id, outside the ownership tree (SM-D23's pattern, extended by OR-130) -- with three
--      differences the brief's shape does not: a revision for optimistic concurrency, a used_by_count the
--      delete guard reads, and a hard DELETE (a Connection, unlike a Target, leaves no row once deleted).
--      attributes/secret_status are both jsonb; attributes NEVER carries a secret-typed VALUE (I6) -- that is
--      the calling layer's job (Task 3/4, which knows the connection type's descriptor), not this migration's
--      or the runtime role's to enforce.
--   3. record_change_audit() replaced ONCE: V018's body plus the four operation names below and the
--      'connection' row_key branch. Every earlier entry is kept (a dropped entry would make sm_cp_runtime's
--      earlier writes fail OR-25 as unknown).
--   4. Grants: SELECT, INSERT, DELETE and a narrow UPDATE on sm_control.connection. Unlike target, the runtime
--      role may DELETE here -- a Connection has no retired state, so deleteConnection's effect IS the DELETE.
--
-- New audit operations: CONNECTION_CREATED, CONNECTION_UPDATED, CONNECTION_DEACTIVATED, CONNECTION_DELETED.

-- 1. Permission catalog and role grants (OR-130; mirrors V016's structure precisely). The foreign key
--    role_permission_catalog_fk is why the catalog rows come first: a grant of a verb the catalog does not hold
--    is refused by the database (23503), so an unapproved verb can never be granted by any path.
INSERT INTO sm_control.permission_catalog (permission_id) VALUES
    ('connection.view'),      -- row 44, STANDARD, reach scope: shaped exactly like target.view
    ('connection.create'),    -- row 45, HIGH, named scope (the body's ownershipScope): shaped like target.create
    ('connection.edit'),      -- row 46, HIGH, named scope (the connection's own): shaped like target.edit
    ('connection.delete');    -- row 47, HIGH, named scope: no Target equivalent -- a Target retires, never deletes

-- The Administrator (V003's id ...0001) holds every catalog verb: the four new ones, by name.
INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000001', 'connection.view'),
    ('00000000-0000-4000-8000-000000000001', 'connection.create'),
    ('00000000-0000-4000-8000-000000000001', 'connection.edit'),
    ('00000000-0000-4000-8000-000000000001', 'connection.delete');

-- The Viewer (...0002) and the Operator (...0003) both gain connection.view ONLY (OR-130 section 1.10.1):
-- connection.create/edit/delete stay Administrator-only, mirroring target.create's own Administrator-only shape.
INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000002', 'connection.view'),
    ('00000000-0000-4000-8000-000000000003', 'connection.view');

-- 2. sm_control.connection. Scope columns: a connection's ownership path is resolved through the existing
--    sm_control.scope table (V001), reusing the exact FK pattern target(organization_id, ownership_scope_id)
--    already establishes (V013 lines 64-78) rather than three separate nullable org/sub-org/env columns --
--    scope already holds the canonical org/sub-org/env node and its organization, so a second representation
--    would only be able to drift from it. The brief's "scoped UNIQUE (org_id, sub_org_id, env_id, name)" is
--    realized here as UNIQUE (organization_id, ownership_scope_id, name): two ownership paths canonicalize to
--    the SAME scope row (JdbcScopeStore.canonicalize), so this is the identical uniqueness the brief names.
CREATE TABLE sm_control.connection (
    connection_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    ownership_scope_id uuid NOT NULL,
    -- The owning scope is inside the connection's organization (an install-kind scope has a NULL
    -- organization_id and so can never match) -- same guarantee as target_ownership_in_org (V013).
    CONSTRAINT connection_ownership_in_org FOREIGN KEY (organization_id, ownership_scope_id)
        REFERENCES sm_control.scope (organization_id, scope_id) ON DELETE RESTRICT,
    name text NOT NULL CONSTRAINT connection_name CHECK (length(name) BETWEEN 1 AND 100),
    connection_type text NOT NULL
        CONSTRAINT connection_type_check CHECK (connection_type IN ('solace-broker', 'remote-server')),
    descriptor_version integer NOT NULL CONSTRAINT connection_descriptor_version CHECK (descriptor_version >= 1),
    -- Never a secret-typed VALUE (I6): the calling layer strips those out before this row is ever written.
    attributes jsonb NOT NULL,
    -- Presence metadata only ({"key": true/false}), never a value.
    secret_status jsonb NOT NULL,
    status text NOT NULL DEFAULT 'active' CONSTRAINT connection_status CHECK (status IN ('active', 'inactive')),
    revision bigint NOT NULL DEFAULT 1 CONSTRAINT connection_revision CHECK (revision > 0),
    used_by_count integer NOT NULL DEFAULT 0 CONSTRAINT connection_used_by_count CHECK (used_by_count >= 0),
    created_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    CONSTRAINT connection_name_once UNIQUE (organization_id, ownership_scope_id, name));

-- 3. The audit function. V018's body verbatim with exactly three edits:
--      (1) the OR-25 allow-list gains CONNECTION_CREATED, CONNECTION_UPDATED, CONNECTION_DEACTIVATED,
--          CONNECTION_DELETED;
--      (2) the row_key CASE gains connection (connection_id);
--      (3) the audit_operation CHECK gains the same four, so the vocabulary stays closed on both sides.
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

    IF TG_TABLE_NAME = 'session' AND TG_OP = 'UPDATE'
       AND (v_new_json - ARRAY['last_used_at', 'idle_expires_at']::text[])
         = (v_old_json - ARRAY['last_used_at', 'idle_expires_at']::text[]) THEN
        RETURN NEW;
    END IF;

    v_actor := sm_control.try_uuid(NULLIF(current_setting('sm.audit.actor', true), ''));
    v_operation := NULLIF(current_setting('sm.audit.operation', true), '');
    v_correlation := sm_control.try_uuid(NULLIF(current_setting('sm.audit.correlation_id', true), ''));
    v_client := NULLIF(current_setting('sm.audit.client_address', true), '');

    IF session_user = 'sm_cp_runtime' THEN
        IF v_operation IS NULL OR v_operation NOT IN (
                'LOGIN_SUCCEEDED', 'LOGIN_REFUSED', 'LOGOUT', 'CREDENTIAL_ROTATED',
                'CREDENTIAL_ROTATION_REFUSED', 'LOCKOUT_CLEARED',
                'INSTANCE_DECLARED', 'PACKAGE_LOADED', 'ENTITLEMENT_SET', 'TARGET_REGISTERED',
                'TARGET_RETIRED', 'DASHBOARD_WIDGET_SAVED', 'DASHBOARD_LAYOUT_SET',
                'DASHBOARD_WIDGET_HIDDEN', 'DASHBOARD_RESET', 'INSTANCE_UNDECLARED',
                'HIERARCHY_NODE_CREATED', 'HIERARCHY_NODE_RENAMED',
                'PRINCIPAL_CREATED', 'PRINCIPAL_DEACTIVATED', 'PRINCIPAL_CREDENTIAL_RESET',
                'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED',
                'CONNECTION_CREATED', 'CONNECTION_UPDATED', 'CONNECTION_DEACTIVATED', 'CONNECTION_DELETED') THEN
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
        IF v_actor IS NULL AND v_operation IN ('LOGIN_REFUSED', 'LOCKOUT_CLEARED')
                AND TG_TABLE_NAME <> 'login_throttle' THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % with no actor is only permitted on login_throttle',
                TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                USING ERRCODE = 'OR025';
        END IF;
        IF v_actor IS NULL AND v_operation = 'ENTITLEMENT_SET'
                AND (TG_TABLE_NAME <> 'entitlement' OR v_client <> 'operator-cli') THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % requires an actor principal (a no-actor ENTITLEMENT_SET is only permitted on entitlement, from the operator command)',
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
        WHEN 'connection' THEN
            jsonb_build_object('connection_id', COALESCE(v_new_json ->> 'connection_id', v_old_json ->> 'connection_id'))
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

    IF TG_TABLE_NAME = 'solution_package' THEN
        v_old := v_old - 'document';
        v_new := v_new - 'document';
    END IF;

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
        'INSTANCE_UNDECLARED',
        'HIERARCHY_NODE_CREATED', 'HIERARCHY_NODE_RENAMED',
        'PRINCIPAL_CREATED', 'PRINCIPAL_DEACTIVATED', 'PRINCIPAL_CREDENTIAL_RESET',
        'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED',
        'CONNECTION_CREATED', 'CONNECTION_UPDATED', 'CONNECTION_DEACTIVATED', 'CONNECTION_DELETED'));

CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.connection
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();

CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.connection
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();

-- 4. Grants. Unlike target, the runtime role may DELETE here: a Connection has no retired state, so
--    deleteConnection's effect IS the DELETE. connection_type, descriptor_version, ownership_scope_id and the
--    created_* columns are never in an UPDATE grant: the type is fixed at creation (contract: updateConnection
--    refuses a body naming connectionType with 400) and the owner never moves.
GRANT SELECT, INSERT, DELETE ON TABLE sm_control.connection TO sm_cp_runtime;
GRANT UPDATE (name, attributes, secret_status, status, used_by_count, revision, updated_by, updated_at)
    ON TABLE sm_control.connection TO sm_cp_runtime;
