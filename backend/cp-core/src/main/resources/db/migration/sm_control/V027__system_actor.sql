-- ==== V022 =============================================================================================================
-- sm_control: the SYSTEM ACTOR, and the OR-25 amendment that lets a scheduled job's write be audited to it (plan 015 T4.6;
-- OR-140; BE-D70, BE-D71, AGW-D28 item 1). Number V022 (measured 2026-10-11 on 1b79a45f: V021 is the head, plan 015 W1).
-- RENUMBERED V027 by the reconciliation into UAT (OR-147, 2026-10-12): plan 015's V021 is now V026, and this body is V026's,
-- which carries the Connections edits of V021/V024/V025 (the CONNECTION_* operations and the 'connection' row key); so does this.
-- Run by Flyway as sm_cp_migrator after SET ROLE sm_cp_owner. Never edit once applied (OR-22).
--
-- OR-140 (owner, 2026-10-11; plan 015 section 10 Q-C1, Q-C2): the agent-enrolment sweep leaves listTargets and runs as a scheduled
-- job; its two control-plane writes (the revoke of an abandoned registration, the retire of its target) are audited to a system
-- actor, never to the token's issuer or to the requester. This migration is that actor and the database side of the amendment.
--
-- The audit function is replaced WHOLE from V021's body, as V021 replaced V020's. V021's markers (every AGENT-EDIT and the V020
-- markers inside them) are kept verbatim; the edits SYS-EDIT 1..4 are marked inside it, so the finalization is a diff of that body
-- against whichever migration last replaces it. The V020 and V021 readiness stages still pass against this body and V022 adds its own.

-- ---- 1. the system actor: a registry of the named non-human actors --------------------------------------------------
-- One seeded row. The runtime role has NO grant on it (it cannot mint an actor); the audit function (SECURITY DEFINER) reads it
-- to refuse an unknown id, and change_audit's foreign key names it.
CREATE TABLE sm_control.system_actor (
    system_actor_id uuid PRIMARY KEY,
    name text NOT NULL CONSTRAINT system_actor_name CHECK (name ~ '^[a-z][a-z0-9-]{0,62}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT system_actor_name_uq UNIQUE (name));
INSERT INTO sm_control.system_actor (system_actor_id, name)
    VALUES ('00000000-0000-4000-8000-000000000a01', 'agent-jobs');   -- SystemActors.AGENT_JOBS: the two OR-140 jobs share it

CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.system_actor
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();

-- ---- 2. the audit trail learns the system actor ---------------------------------------------------------------------
-- change_audit_actor_one_kind: at most one kind of actor per row, now of three. The constraint keeps its name.
ALTER TABLE sm_control.change_audit ADD COLUMN actor_system_id uuid NULL;
ALTER TABLE sm_control.change_audit DROP CONSTRAINT change_audit_actor_one_kind;
ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_actor_one_kind
    CHECK (num_nonnulls(actor_principal_id, actor_agent_id, actor_system_id) <= 1);
ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_actor_system_fk
    FOREIGN KEY (actor_system_id) REFERENCES sm_control.system_actor (system_actor_id) ON DELETE RESTRICT;
-- No new audit_operation: the system actor performs the existing AGENT_REVOKED and TARGET_RETIRED, which is what makes a reader
-- see "the sweep revoked this" in the same vocabulary as "an operator revoked this", with a different kind of actor.

-- ---- 3. the audit function, replaced whole ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sm_control.record_change_audit() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = sm_control, pg_temp
    AS $$
DECLARE
    -- AGW-EDIT 5: the enrolment token's digest is a secret column (never copied into an audit row).
    v_secret_cols text[] := ARRAY['credential_hash', 'token_hash', 'enrollment_token_digest'];
    v_actor uuid;
    v_actor_agent uuid;                              -- AGW-EDIT 1 (BE-D70): an agent as the actor
    v_actor_system uuid;                             -- SYS-EDIT 1 (OR-140): a scheduled job's system actor
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
    -- AGW-EDIT 2: the agent actor, supplied by the same transaction-local mechanism as the principal.
    v_actor_agent := sm_control.try_uuid(NULLIF(current_setting('sm.audit.actor_agent', true), ''));
    -- SYS-EDIT 2 (OR-140): the system actor, supplied by the same transaction-local mechanism.
    v_actor_system := sm_control.try_uuid(NULLIF(current_setting('sm.audit.actor_system', true), ''));

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
                'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED',
                -- OR-147 (reconciliation into UAT): the Connections operations V021/V024 added, kept
                'CONNECTION_CREATED', 'CONNECTION_UPDATED', 'CONNECTION_DEACTIVATED', 'CONNECTION_DELETED',
                -- AGW-EDIT 3: the four agent operations (the plan names the first two; issue and revoke are principal acts)
                'AGENT_ENROLMENT_ISSUED', 'AGENT_ENROLLED', 'AGENT_INSTANCE_REGISTERED', 'AGENT_REVOKED') THEN
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
        -- SYS-EDIT 3 (OR-140 amends OR-25's actor rule): a write audited to the SYSTEM ACTOR is allowed only for the two named job
        -- operations, each on its one table and one kind of change, from the job client address, with no other kind of actor. It is
        -- checked FIRST so that a system actor on an agent operation (AGENT_ENROLLED and the rest) is refused by THIS rule and not
        -- accepted by the agent block below. Every other operation still fails closed without a principal (the actor test further
        -- down is unchanged apart from stepping aside for a system actor that has passed this block).
        IF v_actor_system IS NOT NULL THEN
            IF v_operation NOT IN ('AGENT_REVOKED', 'TARGET_RETIRED') THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: a system actor is only permitted for AGENT_REVOKED and TARGET_RETIRED, not %',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                    USING ERRCODE = 'OR025';
            END IF;
            IF v_actor IS NOT NULL OR v_actor_agent IS NOT NULL THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % requires exactly a system actor and no other kind of actor',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                    USING ERRCODE = 'OR025';
            END IF;
            IF v_client <> 'system-job' THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: a system actor writes only from the job client address system-job',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME
                    USING ERRCODE = 'OR025';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM sm_control.system_actor s WHERE s.system_actor_id = v_actor_system) THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % is not a known system actor',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME, v_actor_system
                    USING ERRCODE = 'OR025';
            END IF;
            --   AGENT_REVOKED: UPDATE of an agent_registration from active to revoked (the sweep's revoke).
            --   TARGET_RETIRED: UPDATE of an AGENT-transport target from live to retired (the sweep's retire); never an INSERT,
            --     never an ssh-docker target.
            IF NOT ((v_operation = 'AGENT_REVOKED' AND TG_TABLE_NAME = 'agent_registration' AND TG_OP = 'UPDATE'
                     AND v_old_json ->> 'status' = 'active' AND v_new_json ->> 'status' = 'revoked'
                     -- column-narrow: revokeIfActive writes exactly status, revoked_at and the agent epoch; a system revoke that also
                     -- detaches the target or rewrites the certificate is refused (review MINOR 4)
                     AND (v_new_json - ARRAY['status', 'revoked_at', 'agent_revocation_epoch']::text[])
                       = (v_old_json - ARRAY['status', 'revoked_at', 'agent_revocation_epoch']::text[]))
                    OR (v_operation = 'TARGET_RETIRED' AND TG_TABLE_NAME = 'target' AND TG_OP = 'UPDATE'
                        AND v_old_json ->> 'transport' = 'agent'
                        AND v_old_json ->> 'retired_at' IS NULL AND v_new_json ->> 'retired_at' IS NOT NULL)) THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: the system actor may write only the revocation of an active agent registration (AGENT_REVOKED) and the retirement of a live agent target (TARGET_RETIRED)',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME
                    USING ERRCODE = 'OR025';
            END IF;
        END IF;
        -- AGW-EDIT 4 (BE-D70; OR-128 (5) records the owner's amendment of OR-25): AGENT_ENROLLED and AGENT_INSTANCE_REGISTERED are
        -- written BY THE AGENT and require an agent actor and NO principal. No other operation may carry an agent actor, so a
        -- leaked setting cannot launder a principal's act into an agent's. Correlation id and client address stay mandatory
        -- (checked above); on this path they come from the gateway-minted AgentRequestOrigin. The exemption is SCOPED the way
        -- every other no-actor exemption in this function is (m3, Q-E): each agent operation may write only the tables its path
        -- writes, and only for a registration that is ACTIVE and belongs to the row's own organization; AGENT_INSTANCE_REGISTERED
        -- additionally needs the registration to be consumed (a registered agent). (Review R-2.)
        IF v_operation IN ('AGENT_ENROLLED', 'AGENT_INSTANCE_REGISTERED') THEN
            IF v_actor_agent IS NULL OR v_actor IS NOT NULL THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % requires exactly an agent actor and no principal',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                    USING ERRCODE = 'OR025';
            END IF;
            IF (v_operation = 'AGENT_ENROLLED' AND TG_TABLE_NAME NOT IN ('agent_registration', 'target'))
               OR (v_operation = 'AGENT_INSTANCE_REGISTERED' AND TG_TABLE_NAME NOT IN ('solution', 'instance', 'scope')) THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % is not permitted on this table',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME, v_operation
                    USING ERRCODE = 'OR025';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM sm_control.agent_registration r
                            WHERE r.agent_id = v_actor_agent
                              AND r.status = 'active'
                              AND r.organization_id::text = COALESCE(v_new_json ->> 'organization_id', v_old_json ->> 'organization_id')
                              AND (v_operation = 'AGENT_ENROLLED' OR r.enrollment_used_at IS NOT NULL)) THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: the agent actor is not an active registration of the row''s organization',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME
                    USING ERRCODE = 'OR025';
            END IF;
            -- T1.3 review finding 1 (R-2 scoped the organization only): the exemption is scoped to the agent's OWN acts. An
            -- active agent of the organization could otherwise mint a registration (a principal act), rewrite another agent's
            -- certificate, retire any target or instance, insert targets of any transport, or register into any environment
            -- or solution of the organization. Exactly what each operation's path writes (T2.2, T3.5, T4.1, T4.2) and no more:
            --   AGENT_ENROLLED: UPDATE of its OWN registration while its token is unconsumed (the single consumption), and
            --     INSERT of its one agent-transport target, named as its token names it (the live-name index makes a second
            --     one a 23505) while its registration has no target yet; never a target retire, never an INSERT of a registration.
            --   AGENT_INSTANCE_REGISTERED: INSERT only (no retire), into its enrolled environment, and a solution row only of
            --     its enrolled catalog solution.
            IF v_operation = 'AGENT_ENROLLED' THEN
                IF NOT ((TG_TABLE_NAME = 'agent_registration' AND TG_OP = 'UPDATE'
                         AND v_old_json ->> 'agent_id' = v_actor_agent::text
                         AND v_new_json ->> 'agent_id' = v_actor_agent::text
                         AND v_old_json ->> 'enrollment_used_at' IS NULL)
                        OR (TG_TABLE_NAME = 'target' AND TG_OP = 'INSERT'
                            AND v_new_json ->> 'transport' = 'agent'
                            AND EXISTS (SELECT 1 FROM sm_control.agent_registration r
                                         WHERE r.agent_id = v_actor_agent
                                           AND r.target_id IS NULL
                                           AND r.target_name = v_new_json ->> 'name'))) THEN
                    RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: the agent actor may write only its own unconsumed registration and its one agent target',
                        TG_TABLE_SCHEMA, TG_TABLE_NAME
                        USING ERRCODE = 'OR025';
                END IF;
            ELSIF NOT (TG_OP = 'INSERT'
                       AND EXISTS (SELECT 1 FROM sm_control.agent_registration r
                                    WHERE r.agent_id = v_actor_agent
                                      AND r.organization_unit_id::text = v_new_json ->> 'organization_unit_id'
                                      AND r.environment_id::text = v_new_json ->> 'environment_id'
                                      AND (TG_TABLE_NAME <> 'solution'
                                           OR r.catalog_solution_id::text = v_new_json ->> 'catalog_solution_id'))) THEN
                RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: the agent actor may write only new rows of its enrolled environment and catalog solution',
                    TG_TABLE_SCHEMA, TG_TABLE_NAME
                    USING ERRCODE = 'OR025';
            END IF;
        ELSIF v_actor_agent IS NOT NULL THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: an agent actor is only permitted for AGENT_ENROLLED and AGENT_INSTANCE_REGISTERED',
                TG_TABLE_SCHEMA, TG_TABLE_NAME
                USING ERRCODE = 'OR025';
        END IF;
        -- SYS-EDIT 4: a system actor that passed the SYS-EDIT 3 block above is an actor; everything else still needs a principal.
        IF v_actor IS NULL AND v_actor_system IS NULL AND v_operation NOT IN ('LOGIN_REFUSED', 'LOCKOUT_CLEARED', 'ENTITLEMENT_SET',
                                                    'HIERARCHY_NODE_RENAMED',
                                                    'AGENT_ENROLLED', 'AGENT_INSTANCE_REGISTERED') THEN
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
        -- V020 (OR-131 Q1, "in the EntitlementMain pattern"): HIERARCHY_NODE_RENAMED may carry no actor, scoped exactly
        -- as the ENTITLEMENT_SET exemption above: the organization table only, and only from the operator command
        -- (OrganizationRenameMain), whose client address is the constant 'operator-cli'. The HTTP rename names the
        -- authenticated principal, so an HTTP-origin no-actor rename (any table, any other client address) is refused
        -- by the database. The marker string 'no-actor HIERARCHY_NODE_RENAMED' is what the readiness stage looks for.
        IF v_actor IS NULL AND v_operation = 'HIERARCHY_NODE_RENAMED'
                AND (TG_TABLE_NAME <> 'organization' OR v_client <> 'operator-cli') THEN
            RAISE EXCEPTION 'OR-25: sm_cp_runtime write to %.% refused: % requires an actor principal (a no-actor HIERARCHY_NODE_RENAMED is only permitted on organization, from the operator command)',
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
        -- OR-147: the Connections row key V021/V025 added, kept
        WHEN 'connection' THEN
            jsonb_build_object('connection_id', COALESCE(v_new_json ->> 'connection_id', v_old_json ->> 'connection_id'))
        WHEN 'dashboard' THEN
            jsonb_build_object('dashboard_id', COALESCE(v_new_json ->> 'dashboard_id', v_old_json ->> 'dashboard_id'))
        -- AGW-EDIT 6
        WHEN 'agent_registration' THEN
            jsonb_build_object('agent_id', COALESCE(v_new_json ->> 'agent_id', v_old_json ->> 'agent_id'))
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

    -- AGW-EDIT 7: an agent registration's issued certificate chain and trust bundle are public but large and are kept in the
    -- row itself; the audit row names the change by column and never copies them.
    IF TG_TABLE_NAME = 'agent_registration' THEN
        v_old := v_old - ARRAY['certificate_chain_der', 'trust_bundle_pem']::text[];
        v_new := v_new - ARRAY['certificate_chain_der', 'trust_bundle_pem']::text[];
    END IF;

    SELECT array_agg(c ORDER BY c) INTO v_secret_changed
      FROM unnest(COALESCE(v_changed, ARRAY[]::text[])) AS c
     WHERE c = ANY (v_secret_cols);

    -- SYS-EDIT 4 (continued): the audit row carries the system actor.
    INSERT INTO sm_control.change_audit (
        recorded_at, txid, table_name, operation, row_key, changed_columns,
        old_values, new_values, secret_columns_changed, actor_principal_id, actor_agent_id, actor_system_id,
        audit_operation, correlation_id, client_address, db_role)
    VALUES (
        clock_timestamp(), txid_current(), TG_TABLE_NAME, TG_OP, v_row_key,
        COALESCE(v_changed, ARRAY[]::text[]), v_old, v_new,
        COALESCE(v_secret_changed, ARRAY[]::text[]), v_actor, v_actor_agent, v_actor_system, v_operation, v_correlation, v_client,
        session_user::text);

    RETURN COALESCE(NEW, OLD);
END;
$$;
