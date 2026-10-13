-- Plan 013 T2.1 (OR-121; D-REG 10.1): what the registration API's control-plane half needs. Never edit this file once
-- applied (OR-22): a correction is a new migration, never an edit here.
--
-- Contents, in this order:
--   1. Grants for the hierarchy writes (create a sub-organization or environment; rename any of the three nodes).
--   2. Grants for the operator-user writes (plan 013 W3: OQ-6 (a) is ruled, so W3 runs): create and deactivate a local
--      principal, grant and revoke a role binding. role_binding is the ONLY table the runtime role may DELETE from;
--      principal is never deleted (a deactivated principal keeps its audit history).
--   3. target_name_once becomes a partial unique index, so a RETIRED target frees its name (OQ-11 (b)).
--   4. record_change_audit() replaced ONCE: V017's body (which already carries INSTANCE_UNDECLARED) plus the seven
--      operation names below, plus the narrowing of the no-actor ENTITLEMENT_SET exemption to the operator command.
--      This is a merge of two bodies, not a renumbering. Every earlier entry is kept: a dropped entry would make
--      sm_cp_runtime's earlier writes fail OR-25 as unknown.
--
-- What this migration deliberately does NOT contain:
--   * the name columns and the per-parent, case-insensitive unique indexes: they are OR-120's and OR-123's V015
--     (organization_name_ci_uq, organization_unit_name_ci_uq, environment_name_ci_uq). T2.1 re-measured that nothing
--     here needs another constraint: the registration API relies on those three indexes, and maps a 23505 on them to
--     the named conflict (OR-123).
--   * the permission catalog's three registration verbs and their Administrator rows: plan 012 T1's V016 (OQ-7 (a), so
--     no Operator row for them).
--   * any INSERT on organization (OQ-5 (a): organizations are not created over HTTP) or on scope (scope rows are minted
--     lazily by ScopeStore.canonicalize, as the target and declare paths do today).
--
-- New audit operations (the row keys for organization, organization_unit, environment, principal and role_binding
-- already exist in the CASE below, V011/V013, so only the names are new):
--   HIERARCHY_NODE_CREATED, HIERARCHY_NODE_RENAMED,
--   PRINCIPAL_CREATED, PRINCIPAL_DEACTIVATED, PRINCIPAL_CREDENTIAL_RESET, ROLE_BINDING_GRANTED, ROLE_BINDING_REVOKED.

-- 1. Hierarchy writes. A sub-organization is created TOP-LEVEL ONLY (SM-D32 / OR-16 fix the depth): the INSERT grant
--    omits parent_unit_id, so the nested column can never be set by the runtime role and stays NULL. An environment's
--    parents are its composite key. The name is the only column a rename may change.
GRANT INSERT (organization_id, organization_unit_id, name) ON TABLE sm_control.organization_unit TO sm_cp_runtime;
GRANT INSERT ON TABLE sm_control.environment TO sm_cp_runtime;
GRANT UPDATE (name) ON TABLE sm_control.organization TO sm_cp_runtime;
GRANT UPDATE (name) ON TABLE sm_control.organization_unit TO sm_cp_runtime;
GRANT UPDATE (name) ON TABLE sm_control.environment TO sm_cp_runtime;

-- 2. The operator user (W3). The credential column is set at INSERT (Argon2id hash, OR-7); a reset goes through the
--    existing UPDATE (credential_hash, must_rotate) grant (V008). status and deactivated_at move together (V002's
--    principal_deactivation_state CHECK). No DELETE on principal.
GRANT INSERT ON TABLE sm_control.principal TO sm_cp_runtime;
GRANT UPDATE (status, deactivated_at) ON TABLE sm_control.principal TO sm_cp_runtime;
GRANT INSERT, DELETE ON TABLE sm_control.role_binding TO sm_cp_runtime;

-- 3. A retired target frees its name (OQ-11 (b)). The index keeps the old constraint's NAME on purpose: a 23505
--    reports the index name, so the one string the callers recognise does not change.
ALTER TABLE sm_control.target DROP CONSTRAINT target_name_once;
CREATE UNIQUE INDEX target_name_once ON sm_control.target (organization_id, name) WHERE retired_at IS NULL;

-- 4. The audit function. V017's body verbatim with exactly THREE edits:
--      (1) the OR-25 allow-list gains the seven operations named above;
--      (2) the no-actor ENTITLEMENT_SET exemption is narrowed to client_address = 'operator-cli' (R16);
--      (3) the audit_operation CHECK gains the same seven, so the vocabulary stays closed on both sides.
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
                'DASHBOARD_WIDGET_HIDDEN', 'DASHBOARD_RESET', 'INSTANCE_UNDECLARED',
                'HIERARCHY_NODE_CREATED', 'HIERARCHY_NODE_RENAMED',
                'PRINCIPAL_CREATED', 'PRINCIPAL_DEACTIVATED', 'PRINCIPAL_CREDENTIAL_RESET',
                'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED') THEN
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
        -- Plan 013 T2.1 (OR-121, R16) narrows it once more: a no-actor ENTITLEMENT_SET is the operator COMMAND's
        -- (EntitlementMain), whose client address is the constant 'operator-cli'. Over HTTP the entitlement is set by
        -- an authenticated principal (PUT /cp/entitlements/{solution}, entitlement.set), so an HTTP-origin write with
        -- no actor is refused by the database, not only by the code. v_client is non-null here (checked above).
        -- The message keeps the 'requires an actor principal' wording: for every other table this IS the
        -- refusal an actor-less write to it has always been.
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
        'INSTANCE_UNDECLARED',
        'HIERARCHY_NODE_CREATED', 'HIERARCHY_NODE_RENAMED',
        'PRINCIPAL_CREATED', 'PRINCIPAL_DEACTIVATED', 'PRINCIPAL_CREDENTIAL_RESET',
        'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED'));
