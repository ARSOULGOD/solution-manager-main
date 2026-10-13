-- ==== VCA ============================================================================================================
-- sm_control: scoped agent registration with the target envelope; the audit function learns the agent actor; catalog
-- rows 44-47 (plan 015 T1.3; OR-126 A8, OR-128 (4) (5); BE-D70, BE-D71, AGW-D24). Number V021 (measured 2026-10-09 on 2568862f: V019 = OR-125 (4) username-per-organization, V020 = OR-131 Q1 operator rename).
--
-- RENUMBERED V026 by the reconciliation into UAT (OR-147, 2026-10-12). UAT's Connections work had already taken V021 (the
-- connection.* verbs as catalog rows 44-47 and a V018-based audit body), V024 and V025 (both patch that body); the old agent's
-- V022/V023 were removed. No database that matters ran either chain (OR-147 (2)), so this migration was edited, once, to stand on
-- the reconciled chain: (a) the agent verbs are catalog rows 48-51 (the catalog is 51; Administrator 51, Operator 27, Viewer 10);
-- (b) the audit function below is still V020's body plus AGW-EDIT 1..7, and now ALSO carries the Connections edits V021/V024/V025
-- made (the four CONNECTION_* operations and the 'connection' row key), so the body this migration installs drops nothing an
-- earlier migration added; (c) the change_audit_audit_operation CHECK names the CONNECTION_* operations too. It also restores the
-- V020 gate that V021's V018-based body had dropped.
-- Run by Flyway as sm_cp_migrator after SET ROLE sm_cp_owner. Never edit once applied (OR-22).
--
-- The audit function is replaced WHOLE from the latest body — V020's (W0 fix round: the earlier draft rebuilt V018's body, which
-- would have dropped OR-131 Q1's gate and failed ControlPlanePersistenceConfiguration.V020_AUDIT_FUNCTION_QUERY, which refuses
-- to start unless the body contains 'no-actor HIERARCHY_NODE_RENAMED' and 'operator-cli'). V020's two edits and its marker
-- comment are kept verbatim; the edits AGW-EDIT 1..7 are marked inside it, so the finalization is a diff of that body against
-- whichever migration last replaces it, not a re-derivation. VCA's readiness stage still requires the V020 markers plus its own.

-- ---- 1. the audit trail learns the agent actor ---------------------------------------------------------------------
-- change_audit_actor_one_kind: at most one kind of actor per row. An agent actor is a registered agent: the foreign key
-- is added after agent_registration exists, below.
ALTER TABLE sm_control.change_audit ADD COLUMN actor_agent_id uuid NULL;
ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_actor_one_kind
    CHECK (actor_principal_id IS NULL OR actor_agent_id IS NULL);
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
        'CONNECTION_CREATED', 'CONNECTION_UPDATED', 'CONNECTION_DEACTIVATED', 'CONNECTION_DELETED',
        'AGENT_ENROLMENT_ISSUED', 'AGENT_ENROLLED', 'AGENT_INSTANCE_REGISTERED', 'AGENT_REVOKED'));
-- [CONDITIONAL] AGENT_LEASE_ISSUED joins both lists ONLY if T2.2 Step 0 finds that issuing or renewing a lease writes an
-- sm_control row. The branch's JdbcAgentRegistry updates only at token consumption and certificate issue (measured by the
-- plan), so it is NOT added here.

-- ---- 2. the agent registration: scoped, with the envelope its target is created with --------------------------------
-- The branch's V014 + V015 merged, WITH scope (I5, OR-27) and WITH the target envelope (P15-2), and WITHOUT target_id
-- text / binding_ids[] (AGW-D24: the CP never invents a placement set). The registration is the enrolment TOKEN's record
-- until it is consumed, and the agent's CP-side identity afterwards.
CREATE TABLE sm_control.agent_registration (
    organization_id uuid NOT NULL REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    organization_unit_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    -- OR-27: instances auto-register into the environment AND solution the agent was enrolled into. The plan's T1.3 and
    -- T4.1 both source these from "the enrolment record", but T0.3 Step 2's issue body names neither: DRAFT FINDING D-C1,
    -- resolved in the console draft by adding both to the body (required, the catalog id and one of its loaded versions).
    catalog_solution_id uuid NOT NULL,
    -- ONE member of the solution's loaded package_version ARRAY, as canonical JSON (e.g. 1 or "v2"): solution_package.package_version
    -- holds the whole array and declare matches a member with jsonb @> (JdbcCatalogRepository), so an equality foreign key cannot
    -- hold a single version. A trigger below is the structural second enforcer of the service's check. (Review R-4.)
    package_version text NOT NULL,
    target_name text NOT NULL CONSTRAINT agent_registration_target_name CHECK (length(target_name) BETWEEN 1 AND 128),
    -- The envelope, immutable, in the grammars of sm_data.target_connection (V007) and registerTarget.
    allowed_config_root text NOT NULL
        CONSTRAINT agent_registration_config_root CHECK (
            allowed_config_root ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$' AND strpos(allowed_config_root, '..') = 0),
    allowed_container_prefix text NOT NULL
        CONSTRAINT agent_registration_container_prefix CHECK (allowed_container_prefix ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    target_id uuid NULL,                             -- set at consumption (T3.5); bound to the organization by the composite FK below
    -- OR-128 (9): the token is 256 random bits, so an unkeyed SHA-256 digest is enough; the preimage is shown once.
    enrollment_token_digest bytea NOT NULL CONSTRAINT agent_registration_token_digest CHECK (octet_length(enrollment_token_digest) = 32),
    enrollment_csr_digest bytea NULL CONSTRAINT agent_registration_csr_digest CHECK (octet_length(enrollment_csr_digest) = 32),
    enrollment_used_at timestamptz NULL,
    expires_at timestamptz NOT NULL,
    -- Fixed by the server at issue, never a body field: an agent with no capability refuses every command. report-only is not issued
    -- (AGW-12 is routed). (Review R-17.)
    capabilities text[] NOT NULL DEFAULT ARRAY['apply-config', 'lifecycle']::text[]
        CONSTRAINT agent_registration_capabilities CHECK (capabilities <@ ARRAY['apply-config', 'lifecycle', 'report-only']::text[]),
    agent_revocation_epoch bigint NOT NULL DEFAULT 1 CONSTRAINT agent_registration_agent_epoch CHECK (agent_revocation_epoch > 0),
    fleet_revocation_epoch bigint NOT NULL DEFAULT 1 CONSTRAINT agent_registration_fleet_epoch CHECK (fleet_revocation_epoch > 0),
    lease_ttl_seconds bigint NOT NULL DEFAULT 300 CONSTRAINT agent_registration_lease_ttl CHECK (lease_ttl_seconds BETWEEN 30 AND 86400),
    admission_margin_seconds bigint NOT NULL DEFAULT 5 CONSTRAINT agent_registration_margin CHECK (admission_margin_seconds BETWEEN 0 AND 300),
    audience text NOT NULL DEFAULT 'solution-manager-agent',
    status text NOT NULL DEFAULT 'active' CONSTRAINT agent_registration_status CHECK (status IN ('active', 'revoked')),
    revoked_at timestamptz NULL,
    certificate_chain_der bytea[] NULL,
    trust_bundle_pem bytea[] NULL,
    valid_from timestamptz NULL,
    valid_to timestamptz NULL,
    created_by uuid NOT NULL REFERENCES sm_control.principal (id) ON DELETE RESTRICT,   -- the principal who ISSUED the token (OR-128 (5))
    created_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id, agent_id),
    CONSTRAINT agent_registration_agent_id_global UNIQUE (agent_id),
    CONSTRAINT agent_registration_token_once UNIQUE (enrollment_token_digest),
    CONSTRAINT agent_registration_environment_fk FOREIGN KEY (organization_id, organization_unit_id, environment_id)
        REFERENCES sm_control.environment (organization_id, organization_unit_id, environment_id) ON DELETE RESTRICT,
    CONSTRAINT agent_registration_solution_fk FOREIGN KEY (organization_id, catalog_solution_id)
        REFERENCES sm_control.catalog_solution (organization_id, catalog_solution_id) ON DELETE RESTRICT,
    CONSTRAINT agent_registration_revoked CHECK ((status = 'revoked') = (revoked_at IS NOT NULL)),
    CONSTRAINT agent_registration_certificate_window CHECK (
        (certificate_chain_der IS NULL AND trust_bundle_pem IS NULL AND valid_from IS NULL AND valid_to IS NULL)
        OR (certificate_chain_der IS NOT NULL AND cardinality(certificate_chain_der) > 0
            AND trust_bundle_pem IS NOT NULL AND cardinality(trust_bundle_pem) > 0
            AND valid_from IS NOT NULL AND valid_to IS NOT NULL AND valid_to > valid_from)),
    -- consumption is one act: the used-at, the CSR digest, the issued certificate and the target id arrive together (T2.2, T3.5)
    CONSTRAINT agent_registration_consumed CHECK (
        (enrollment_used_at IS NULL) = (target_id IS NULL AND certificate_chain_der IS NULL AND enrollment_csr_digest IS NULL)
        AND (enrollment_used_at IS NULL OR (target_id IS NOT NULL AND certificate_chain_der IS NOT NULL AND enrollment_csr_digest IS NOT NULL))));
-- One live (unconsumed or active) target name per organization is pre-checked at issue; the CP target row's own
-- target_name_once index (V018) is the structural backstop at enrolment.
-- A pending (unconsumed, active) token holds its target name: a duplicate fails at issue instead of stranding a token at
-- enrolment. An EXPIRED unused token keeps holding it until revokeAgent releases it (the index cannot read the clock).
CREATE UNIQUE INDEX agent_registration_pending_name ON sm_control.agent_registration (organization_id, target_name)
    WHERE status = 'active' AND enrollment_used_at IS NULL;
-- target_id is bound to the registration's own organization (a target of another organization is unrepresentable).
ALTER TABLE sm_control.target ADD CONSTRAINT target_org_target_key UNIQUE (organization_id, target_id);
ALTER TABLE sm_control.agent_registration ADD CONSTRAINT agent_registration_target_fk FOREIGN KEY (organization_id, target_id)
    REFERENCES sm_control.target (organization_id, target_id) ON DELETE RESTRICT;
-- The named package version must be a member of a version array the solution has loaded (the service checks first, as declare does).
CREATE FUNCTION sm_control.agent_registration_package_is_loaded() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- A missing column is NOT NULL's to refuse (a BEFORE ROW trigger fires first): answer 23502 naming the right column,
    -- not a misleading "has not loaded". (T1.3 review finding 3.)
    IF NEW.catalog_solution_id IS NULL OR NEW.package_version IS NULL THEN
        RETURN NEW;
    END IF;
    -- ONE member, never an array: jsonb @> is containment, so '[]' (contained in any array) and any sub-array such as '[1]'
    -- would pass the EXISTS below. (T1.3 review finding 2.)
    IF jsonb_typeof(CAST(NEW.package_version AS jsonb)) NOT IN ('number', 'string') THEN
        RAISE EXCEPTION 'agent_registration package_version must name one version (a JSON number or string), not an array or object'
            USING ERRCODE = 'check_violation', COLUMN = 'package_version';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sm_control.solution_package p
                    WHERE p.catalog_solution_id = NEW.catalog_solution_id
                      AND CAST(p.package_version AS jsonb) @> CAST(NEW.package_version AS jsonb)) THEN
        RAISE EXCEPTION 'agent_registration names a package version the solution has not loaded'
            USING ERRCODE = 'foreign_key_violation', COLUMN = 'package_version';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_registration_package BEFORE INSERT ON sm_control.agent_registration
    FOR EACH ROW EXECUTE FUNCTION sm_control.agent_registration_package_is_loaded();
-- Revocation is sticky on the CP side too (it is in the DP): status never returns to active, epochs never decrease. (Review R-14.)
CREATE FUNCTION sm_control.agent_registration_revocation_is_sticky() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.status = 'revoked' AND NEW.status <> 'revoked')
       OR NEW.agent_revocation_epoch < OLD.agent_revocation_epoch
       OR NEW.fleet_revocation_epoch < OLD.fleet_revocation_epoch THEN
        RAISE EXCEPTION 'an agent revocation is sticky and its epochs only rise'
            USING ERRCODE = 'check_violation', COLUMN = 'status';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_registration_revocation BEFORE UPDATE ON sm_control.agent_registration
    FOR EACH ROW EXECUTE FUNCTION sm_control.agent_registration_revocation_is_sticky();
CREATE INDEX agent_registration_environment ON sm_control.agent_registration (organization_id, organization_unit_id, environment_id);

ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_actor_agent_fk
    FOREIGN KEY (actor_agent_id) REFERENCES sm_control.agent_registration (agent_id) ON DELETE RESTRICT;

-- V013's CP target row gains the agent transport (P15-2; found by this draft: the plan's VCA text does not mention it).
ALTER TABLE sm_control.target DROP CONSTRAINT target_transport;
ALTER TABLE sm_control.target ADD CONSTRAINT target_transport CHECK (transport IN ('ssh-docker', 'agent'));
-- OR-111: v1 agent targets are Docker only (T1.3 review finding 10); the check binds the agent's own INSERT as well.
ALTER TABLE sm_control.target ADD CONSTRAINT target_agent_is_docker CHECK (transport <> 'agent' OR kind = 'docker');

CREATE TRIGGER change_audit_trigger AFTER INSERT OR UPDATE OR DELETE ON sm_control.agent_registration
    FOR EACH ROW EXECUTE FUNCTION sm_control.record_change_audit();
CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.agent_registration
    FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation();

-- Grants. INSERT: the issuing principal's act. UPDATE is column-scoped to exactly the two later writers: the agent's
-- consumption (token use, CSR, issued material, target id) and the principal's revocation (status, epochs).
GRANT SELECT, INSERT ON sm_control.agent_registration TO sm_cp_runtime;
GRANT UPDATE (enrollment_used_at, enrollment_csr_digest, certificate_chain_der, trust_bundle_pem, valid_from, valid_to,
              target_id) ON sm_control.agent_registration TO sm_cp_runtime;
GRANT UPDATE (status, revoked_at, agent_revocation_epoch, fleet_revocation_epoch) ON sm_control.agent_registration TO sm_cp_runtime;

-- ---- 3. rows 48-51 (44-47 before OR-147) of the permission catalog (OR-126 A8; levels and scope: OR-128 (4)) ------------
-- The catalog is the permission_catalog table (V003/V014/V016/V021). The counts after this migration: 51 / Operator 27 / Viewer 10.
INSERT INTO sm_control.permission_catalog (permission_id) VALUES
    ('agent.enrol'),      -- row 48, HIGH, environment scope: issue an enrolment token (also needs target.create)
    ('agent.revoke'),     -- row 49, HIGH, environment scope: revoke an agent
    ('agent.drain'),      -- row 50, STANDARD, environment scope: ask an agent to drain and reconnect
    ('agent.view');       -- row 51, read-only, environment scope
INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000001', 'agent.enrol'),      -- Administrator: all four
    ('00000000-0000-4000-8000-000000000001', 'agent.revoke'),
    ('00000000-0000-4000-8000-000000000001', 'agent.drain'),
    ('00000000-0000-4000-8000-000000000001', 'agent.view'),
    ('00000000-0000-4000-8000-000000000003', 'agent.view'),       -- Operator: view + drain
    ('00000000-0000-4000-8000-000000000003', 'agent.drain'),
    ('00000000-0000-4000-8000-000000000002', 'agent.view');       -- Viewer: view

-- ---- 4. the audit function, replaced whole ---------------------------------------------------------------------------
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
        IF v_actor IS NULL AND v_operation NOT IN ('LOGIN_REFUSED', 'LOCKOUT_CLEARED', 'ENTITLEMENT_SET',
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

    INSERT INTO sm_control.change_audit (
        recorded_at, txid, table_name, operation, row_key, changed_columns,
        old_values, new_values, secret_columns_changed, actor_principal_id, actor_agent_id,
        audit_operation, correlation_id, client_address, db_role)
    VALUES (
        clock_timestamp(), txid_current(), TG_TABLE_NAME, TG_OP, v_row_key,
        COALESCE(v_changed, ARRAY[]::text[]), v_old, v_new,
        COALESCE(v_secret_changed, ARRAY[]::text[]), v_actor, v_actor_agent, v_operation, v_correlation, v_client,
        session_user::text);

    RETURN COALESCE(NEW, OLD);
END;
$$;
