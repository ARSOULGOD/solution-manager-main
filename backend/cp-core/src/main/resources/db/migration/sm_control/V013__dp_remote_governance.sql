-- Plan 010 T2.0: every sm_control table the dp-remote flow needs, in ONE audited migration. Never edit this
-- file once applied (OR-22) — a correction is a new migration, never an edit here.
--
-- One migration, because every new audited table must CREATE OR REPLACE the one shared
-- record_change_audit() (V012), and two parallel migrations each replacing it would silently drop each
-- other's row_key branches.
--
-- THE PRIVILEGE CHANGE OF THIS PLAN: V008 gave sm_cp_runtime SELECT only on the hierarchy tables
-- (organization .. instance, scope). This migration widens that to INSERT on solution, instance and scope —
-- the declare path (T4.3) — and gives it INSERT on the four new tables. It still holds no DELETE anywhere
-- here, and no UPDATE except entitlement's three columns and target.retired_at.

-- OR-27: the instance id is globally unique; the resource directory already looks it up alone
-- (JdbcResourceScopeDirectory) and fails closed on a duplicate. This makes the assumption structural.
ALTER TABLE sm_control.instance ADD CONSTRAINT instance_id_unique UNIQUE (instance_id);

-- OR-41 / OR-14: the org-scoped catalog entry, and the packages loaded under it.
CREATE TABLE sm_control.catalog_solution (
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    catalog_solution_id uuid NOT NULL UNIQUE,
    solution text NOT NULL CONSTRAINT catalog_solution_name CHECK (solution ~ '^[a-z][a-z0-9-]{0,63}$'),
    created_at timestamptz NOT NULL,
    CONSTRAINT catalog_solution_pk PRIMARY KEY (organization_id, solution),
    CONSTRAINT catalog_solution_org_id UNIQUE (organization_id, catalog_solution_id));   -- target of the composite FK below
CREATE TABLE sm_control.solution_package (
    load_seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- the catalog entry's stateVersion source
    organization_id uuid NOT NULL,
    catalog_solution_id uuid NOT NULL,
    -- m9: the package's organization IS its entry's organization — structurally, not by convention.
    CONSTRAINT solution_package_entry FOREIGN KEY (organization_id, catalog_solution_id)
        REFERENCES sm_control.catalog_solution (organization_id, catalog_solution_id) ON DELETE RESTRICT,
    package_version text NOT NULL,       -- canonical text of the package's packageVersion (it is an array, e.g. [1])
    applies_to text NOT NULL,
    digest text NOT NULL CONSTRAINT solution_package_digest CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    signature_status text NOT NULL CONSTRAINT solution_package_signature CHECK (signature_status = 'unsigned'),
                                         -- OR-40: signing is phase 2; the CHECK widens when it lands
    document jsonb NOT NULL,
    loaded_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    loaded_at timestamptz NOT NULL,
    CONSTRAINT solution_package_once UNIQUE (catalog_solution_id, package_version));

-- OR-40: which solutions an organization may run, and how many instances of each.
CREATE TABLE sm_control.entitlement (
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    solution text NOT NULL,
    max_instances integer NOT NULL CONSTRAINT entitlement_positive CHECK (max_instances > 0),
    set_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    set_at timestamptz NOT NULL,
    CONSTRAINT entitlement_pk PRIMARY KEY (organization_id, solution));

-- OR-41: the environment's Solution scope node points at its catalog entry; one node per env per entry.
ALTER TABLE sm_control.solution ADD COLUMN catalog_solution_id uuid NULL
    REFERENCES sm_control.catalog_solution (catalog_solution_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX solution_one_per_catalog_entry ON sm_control.solution
    (organization_id, organization_unit_id, environment_id, catalog_solution_id)
    WHERE catalog_solution_id IS NOT NULL;

-- m9: target of target_ownership_in_org below. scope_id is already the PK, so this adds no new uniqueness.
ALTER TABLE sm_control.scope ADD CONSTRAINT scope_org_id UNIQUE (organization_id, scope_id);

-- OR-28: the target governance row. Connection details live in sm_data (T2.1), never here.
-- Q-C3 (owner ruling 2026-09-29, a): kind is the MI runtime (SM-D*'s VM, Docker, Kubernetes), transport is how
-- SM reaches it; v1's one pair is transport 'ssh-docker' with kind 'docker'.
CREATE TABLE sm_control.target (
    target_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    ownership_scope_id uuid NOT NULL,
    -- m9: the owning scope is inside the target's organization (an install-kind scope has a NULL
    -- organization_id and so can never match).
    CONSTRAINT target_ownership_in_org FOREIGN KEY (organization_id, ownership_scope_id)
        REFERENCES sm_control.scope (organization_id, scope_id) ON DELETE RESTRICT,
    name text NOT NULL CONSTRAINT target_name CHECK (length(name) BETWEEN 1 AND 128),
    kind text NOT NULL CONSTRAINT target_kind CHECK (kind IN ('docker','kubernetes','vm')),
    transport text NOT NULL CONSTRAINT target_transport CHECK (transport IN ('ssh-docker')),
    created_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    CONSTRAINT target_name_once UNIQUE (organization_id, name));
-- The ownership scope's KIND (organization, organization unit or environment — never solution, instance or
-- install) is checked by the registering command; the composite FK above makes a cross-organization owner
-- unrepresentable.

-- OR-25 / OR-19 for the four new tables. The function below is V012's body verbatim with exactly four edits:
--   (1) the OR-25 allow-list gains INSTANCE_DECLARED, PACKAGE_LOADED, ENTITLEMENT_SET, TARGET_REGISTERED,
--       TARGET_RETIRED;
--   (2) the row_key CASE gains catalog_solution, solution_package, entitlement and target;
--   (3) for solution_package only, the document key is removed from old_values/new_values (the row keeps digest);
--   (4) owner ruling 2026-09-29 (Q-E, OR-48): the actor exemption admits ENTITLEMENT_SET with a NULL actor on
--       entitlement only, scoped as LOCKOUT_CLEARED is scoped to login_throttle.

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
                'TARGET_RETIRED') THEN
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
        'INSTANCE_DECLARED', 'PACKAGE_LOADED', 'ENTITLEMENT_SET', 'TARGET_REGISTERED', 'TARGET_RETIRED'));

CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.catalog_solution
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.solution_package
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.entitlement
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.target
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();

CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.catalog_solution
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.solution_package
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.entitlement
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.target
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();

-- Grants. Packages are immutable once loaded (no UPDATE at all, so a document can never be rewritten under
-- its recorded digest); nothing here is ever deleted by the runtime role.
GRANT SELECT, INSERT ON TABLE sm_control.catalog_solution, sm_control.solution_package, sm_control.target
    TO sm_cp_runtime;
GRANT SELECT, INSERT ON TABLE sm_control.entitlement TO sm_cp_runtime;
GRANT UPDATE (max_instances, set_by, set_at) ON TABLE sm_control.entitlement TO sm_cp_runtime;
GRANT UPDATE (retired_at) ON TABLE sm_control.target TO sm_cp_runtime;
-- The declare path (T4.3) writes the hierarchy's lower nodes and their scope rows. Widens V008's SELECT-only grant.
GRANT INSERT ON TABLE sm_control.solution, sm_control.instance, sm_control.scope TO sm_cp_runtime;
