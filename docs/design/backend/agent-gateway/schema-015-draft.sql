-- ====================================================================================================================
-- PLAN 015 T0.3 STEP 3 -- THE DDL DRAFT: VDA, VDB (sm_data) and VCA (sm_control).   STATUS: DRAFT, NOT A MIGRATION.
-- ====================================================================================================================
-- This file lives under docs/, so no migrator, no checksum file and no check reads it. T1.1 and T1.3 turn each of the
-- three sections below into a migration at the numbers T0.1 Step 1 binds (measured 2026-10-09 on 2568862f: V028/V029 and V021), AFTER the dp-remote
-- PR (combined 008/012/013) has merged. The finalization is a DIFF AGAINST THIS DRAFT, reviewed by the W1 reviewer; it is
-- not a second T0.3 review (plan 015, T0.3 Step 3).
--
-- WHAT WAS DRAFT, AND IS NOW BOUND (W0 fix round, 2026-10-09, base 2568862f). OR-125 (1) (the placement actor) is sm_data V026
-- (placement.created_by / retired_by uuid, no kind column, an agent's id goes into the same columns: VDA needs NO placement DDL).
-- OR-125 (2) is sm_data V027 (target_connection.host_key_algorithm text NULL, NULL = type unknown for pre-V027 SSH rows).
-- The audit function in VCA is V020's body (OR-131 Q1: the operator-cli HIERARCHY_NODE_RENAMED gate), with the V020 markers kept
-- verbatim and the seven edits AGW-EDIT 1..7 applied; VCA is sm_control V021 (V019 = OR-125 (4), V020 = OR-131 Q1).
--
-- REVIEW (T0.3 Step 5): the Opus review's findings against this file were applied before the probes described below were re-run: R-1
-- (retention never deletes the highest wire_generation of an (agent, placement)), R-2 (the agent operations are table- and
-- registration-scoped), R-3 (the DP copies solution and package version at enrolment), R-4 (no equality FK on a package version),
-- R-5 (no bytea in agent_%), R-6 (one live command per intent key AND operation), R-13 (one composite reference to the intent,
-- and a trigger tying a command to the agent's own target), R-14 (CP-side stickiness, epochs > 0), R-16 (lease retention),
-- R-17 (server-fixed capabilities, revoked_at), R-21 (target bound to the organization; consumption is one act; pending names
-- unique). Dispositions: proto-amendments-015.md, last section.
--
-- W0 FIX ROUND REPLAY (2026-10-09). Re-run on a throwaway postgres:14 (container removed afterwards) over the real migrations of
-- 2568862f: sm_data V001-V027, sm_metrics V001-V003, sm_control V001-V020, each in one transaction as sm_dp_owner / sm_cp_owner after
-- the repository's phase-0 scripts; a legacy SSH target (host_key_algorithm NULL) inserted BEFORE VDA; then VDA, VDB, VCA of THIS file:
-- all green. Probes: the three the review asked for (N1 the legacy NULL-algorithm row survives VDA; N2 an agent row with the algorithm
-- set is refused 23514; N3 an operator-cli HIERARCHY_NODE_RENAMED on organization is still accepted by a real sm_cp_runtime login)
-- plus 18 more (shape, V012 guard, global uniqueness, no bytea, runtime grants, V020 markers, catalog 47/47/26/9, transport and
-- operation CHECKs): 21 PASS, 0 FAIL. THE ORIGINAL 94 PROBES WERE NOT RE-RUN: their scripts were scratch and were never committed
-- (see below), so no copy exists. The 21 are in evidence/w0-fix-replay/. Sections not exercised by those 21 (agent_command, lease,
-- discovery, the CP agent_registration triggers) were applied without error but not re-probed.
-- WHAT WAS MEASURED (2026-10-08, agw0c). On a throwaway postgres:14 container (no remote host, no Docker state touched
-- beyond it), every sm_data migration V001-V023 of this tree plus V024/V025 read from x/w4-int @ a6cec7c7, and every sm_control
-- migration V001-V018 (the audit function: V018's body, since replaced by V020's, see the fix round below), were applied in order as sm_dp_owner / sm_cp_owner after the repository's own phase-0 scripts, then
-- VDA+VDB and VCA were applied: all green. Then 94 probes ran (61 on sm_data as owner / runtime roles, 33 on sm_control, the
-- runtime ones through a real sm_cp_runtime login so the OR-25 session_user rule fires): every one printed PASS, each refusal by
-- SQLSTATE. They cover the plan's AgentSchemaIT list (T1.1 Step 1) and AgentRegistrationSchemaIT list (T1.3 Step 1). They were
-- scratch SQL scripts and are NOT committed: T1.1 and T1.3 write the ITs. The catalog after VCA counted 47 / Administrator 47 /
-- Operator 26 / Viewer 9.
--
-- FINDINGS THIS DRAFT MADE IN THE PLAN (each is fixed below and needs the plan's T1.1/T1.3 text to follow):
--   G-1  T1.1 would fail on its first agent target. V012's AFTER INSERT/UPDATE triggers copy the pinned host key into
--        target_host_key (fingerprint NOT NULL); an agent row has no pin. VDA replaces target_pinned_key_recorded() with a
--        NULL guard. Reproduced: with the replacement removed, the agent target insert raised
--        23502 on target_host_key.fingerprint; with it, the same insert commits.
--   G-2  T1.3 omits sm_control.target.transport. V013 admits only 'ssh-docker'; the CP target row T3.5 writes at enrolment
--        is transport 'agent'. VCA widens target_transport.
--   G-3  T1.3 names two new audit operations (AGENT_ENROLLED, AGENT_INSTANCE_REGISTERED), both agent acts. Token ISSUE and
--        REVOKE are principal acts that also write agent_registration under the OR-25 trigger and need operation names:
--        AGENT_ENROLMENT_ISSUED and AGENT_REVOKED. Added.
--   G-4  T1.3/T4.1 source the instance's solution and package version from "the enrolment record", but T0.3 Step 2's issue body
--        carries neither. VCA keeps catalog_solution_id + package_version (FK to the loaded package); the console draft adds
--        both to the body. Needs the plan text corrected (and is an owner-visible shape: see the console draft, D-C1).
--   G-5  The enrolment token digest is copied into change_audit.new_values by the generic trigger unless it is a secret column,
--        and the issued certificate chain bloats every audit row. AGW-EDIT 5 and 7.
--   G-6  (i) a command is tied to its intent's placement and to the agent's own target: ADDED after review (R-13). (ii) a trigger tying
--        instance_state.acquisition_mode = 'agent' to placements on an agent target: NOT added (the poller and collector skip by
--        target transport, HF19, so it would be a guard, not a dependency).
-- ====================================================================================================================

-- ==== VDA ============================================================================================================
-- sm_data: the agent gateway's state, over OUR placement and target_connection tables (plan 015 T1.1; BE-D68, BE-D71,
-- AGW-D24..AGW-D30). Number V028 (measured 2026-10-09 on 2568862f: sm_data's latest is V027).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object is born owned by sm_dp_owner.
-- Every table leads with tenant_id (I5); no foreign key leaves sm_data (I7); no column of any agent table can hold a
-- rendered byte or a plain digest of one (I6, OR-32, OR-126 A5): there is no bytea column, no payload column, and the
-- only hash of CONFIGURATION is agent_command.config_hash, the keyed hmacsha256v<n>:<hex> of ConfigHasher. Three other
-- hash-like columns exist and are not config hashes: agent_lease.renewal_nonce_digest (the unkeyed SHA-256 of a one-shot
-- renewal nonce, a bearer secret kept only as a digest) and the two certificate_thumbprint columns (the SHA-256 of a public
-- certificate: an identifier, not a secret). The one free-shape column, agent_lease.verb_ceilings, is CHECKed to a bounded
-- object. AgentSchemaIT pins the exact column set and this allow-list.
--
-- LOCK CLASS: ACCESS EXCLUSIVE on sm_data.target_connection (DROP/ADD CONSTRAINT, DROP NOT NULL, ADD COLUMN, the new
-- unique constraint; every row is re-validated against the shape CHECK), on sm_data.target_connection_event (the kind
-- CHECK, the new column with a DEFAULT, the iff CHECK; every row is re-validated), on sm_data.instance_state (the
-- acquisition-mode CHECK) and on sm_data.apply_step_intent (a 5-column UNIQUE: a unique index build over the never-pruned
-- apply journal, which holds every intent ever written). Everything else creates objects nothing references yet. A
-- maintenance-window migration on an install with a long journal; the new binary does not go Ready on an older schema.
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). It is ONE-WAY once any agent
-- row exists: the SSH columns cannot be set NOT NULL again over an agent target, nor the transport CHECK narrowed. Before
-- the first enrolment, by hand, in this order: DROP the eight agent tables (agent_event first, agent_identity last) and the
-- functions agent_command_placement_on_agents_target(), agent_identity_revocation_is_sticky(),
-- agent_session_epoch_only_rises() and agent_discovery_terminal_is_final(); DROP CONSTRAINT apply_step_intent_command_key;
-- on target_connection DROP the constraints target_connection_has_agent, target_connection_agent_binding and
-- target_connection_transport_shape, DROP INDEX target_connection_agent_once, DROP COLUMN agent_id, re-SET NOT NULL on host,
-- port, ssh_user, host_key_fingerprint and credential_ref, and restore the V007 transport CHECK; restore the CHECKs of
-- target_connection_event (kind without 'enrolled'; DROP COLUMN actor_kind with its two constraints) and
-- instance_state.instance_acquisition_mode ('direct-http', 'via-ssh'); restore V012's target_pinned_key_recorded() body
-- (without the NULL guard) by hand; finally delete the V028 row from flyway_schema_history.

-- ---- 1. the agent target: target_connection widened, never edited (BE-D68) ----------------------------------------
-- V007 made every SSH field NOT NULL and the transport 'ssh-docker' only. An agent target has no address, no user, no
-- pin and no credential: the shape CHECK below makes each transport's fields all-or-nothing, so neither a half SSH row
-- nor an agent row with an address can exist. The envelope (allowed_config_root / allowed_container_prefix) stays NOT
-- NULL for BOTH transports: an agent target carries the envelope its enrolment token named (BE-D71), so V007's
-- placement_envelope trigger holds for agent placements exactly as for SSH ones. created_by stays NOT NULL: on an
-- agent row it is the principal who issued the enrolment token (OR-128 (5)).
-- target_connection_transport is kept (re-added with 'agent') as defence in depth, although the shape CHECK below subsumes it:
-- the pair is intentional and the transport CHECK is the cheaper one to read.
ALTER TABLE sm_data.target_connection DROP CONSTRAINT target_connection_transport;
ALTER TABLE sm_data.target_connection
    ADD CONSTRAINT target_connection_transport CHECK (transport IN ('ssh-docker', 'agent')),
    ALTER COLUMN host DROP NOT NULL,
    ALTER COLUMN port DROP NOT NULL,
    ALTER COLUMN ssh_user DROP NOT NULL,
    ALTER COLUMN host_key_fingerprint DROP NOT NULL,
    ALTER COLUMN credential_ref DROP NOT NULL,
    ADD COLUMN agent_id uuid NULL;
-- OR-125 (2) / sm_data V027: target_connection.host_key_algorithm text NULL (NULL = the pinned key's type is not known, by
-- design, for every pre-V027 SSH row; the connector records it on first success). So the shape CHECK below puts NO constraint
-- on it for the ssh-docker leg (an upgraded database holding a pre-V027 SSH target must still accept this constraint, and V027's
-- fallback must stay legal), and requires it NULL on the agent leg (an agent target has no host key).
ALTER TABLE sm_data.target_connection ADD CONSTRAINT target_connection_transport_shape CHECK (
    (transport = 'ssh-docker' AND agent_id IS NULL
        AND host IS NOT NULL AND port IS NOT NULL AND ssh_user IS NOT NULL
        AND host_key_fingerprint IS NOT NULL AND credential_ref IS NOT NULL)
 OR (transport = 'agent' AND agent_id IS NOT NULL
        AND host IS NULL AND port IS NULL AND ssh_user IS NULL
        AND host_key_fingerprint IS NULL AND host_key_algorithm IS NULL
        AND credential_ref IS NULL AND credential_public_fingerprint IS NULL));
-- One LIVE agent target per agent. (The other direction, agent_identity.agent_id UNIQUE, is below.)
CREATE UNIQUE INDEX target_connection_agent_once ON sm_data.target_connection (tenant_id, agent_id)
    WHERE agent_id IS NOT NULL AND retired_at IS NULL;
-- The referenced key of agent_identity's binding to ITS OWN target (T1.1 review, finding 1). (tenant_id, target_id) is the
-- primary key, so this adds no restriction; it exists so a foreign key can name the agent_id as well. Rows whose agent_id is
-- NULL (every SSH target) can never be referenced by it.
ALTER TABLE sm_data.target_connection ADD CONSTRAINT target_connection_agent_binding UNIQUE (tenant_id, target_id, agent_id);

-- DEFECT FOUND BY THIS DRAFT (not in the plan's T1.1 text): V012's AFTER INSERT/UPDATE triggers copy the PINNED host key
-- into target_host_key, whose fingerprint is NOT NULL. An agent row has no pin, so without this replacement the very
-- first agent target fails 23502. Same body as V012, plus the NULL guard. (target_keys_follow_retirement is untouched:
-- it updates by (tenant, target), and an agent target simply has no rows.)
CREATE OR REPLACE FUNCTION sm_data.target_pinned_key_recorded() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.host_key_fingerprint IS NULL THEN
        RETURN NEW;                                  -- an agent target has no host key to record
    END IF;
    INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint, retired_at)
        VALUES (NEW.tenant_id, NEW.target_id, NEW.host_key_fingerprint, NEW.retired_at)
        ON CONFLICT (tenant_id, target_id, fingerprint) DO NOTHING;
    RETURN NEW;
END $$;

-- V022's change audit gains the enrolment event and the actor kind. 'enrolled' carries no public_key_fingerprint:
-- V022's target_connection_event_fp_iff_key_set already requires NULL for every kind outside registered / rotated.
ALTER TABLE sm_data.target_connection_event
    DROP CONSTRAINT target_connection_event_kind;
ALTER TABLE sm_data.target_connection_event
    ADD CONSTRAINT target_connection_event_kind CHECK (kind IN
        ('registered', 'edited', 'repinned', 'credential_rotated', 'retired', 'enrolled')),
    ADD COLUMN actor_kind text NOT NULL DEFAULT 'principal'
        CONSTRAINT target_connection_event_actor_kind CHECK (actor_kind IN ('principal', 'agent')),
    ADD CONSTRAINT target_connection_event_agent_iff_enrolled CHECK ((kind = 'enrolled') = (actor_kind = 'agent'));
-- OR-125 (1) / sm_data V026: placement.created_by and retired_by are uuid NULL with no kind column, and V026's comment says an
-- agent's id goes into the same columns. So NO placement DDL here: T4.1 writes the agent's id into placement.created_by, and an
-- agent actor is told apart by joining agent_identity (BE-D70 item 6). (An earlier draft proposed an actor_kind column; dropped.)

-- The agent acquisition mode. instance_state.acquisition_mode is the closed pair V007 set; 'agent' is the third.
ALTER TABLE sm_data.instance_state DROP CONSTRAINT instance_acquisition_mode;
ALTER TABLE sm_data.instance_state ADD CONSTRAINT instance_acquisition_mode
    CHECK (acquisition_mode IN ('direct-http', 'via-ssh', 'agent'));

-- ---- 2. the agent's identity -------------------------------------------------------------------------------------
-- The DP's projection of an enrolled agent (AGW-D19). Resolved from the certificate thumbprint by ONE named unbound read
-- (JdbcAgentIdentityRepository#resolveByThumbprint), which is sound only because agent_id and certificate_thumbprint are
-- each GLOBALLY unique: a tenant is derivable from either (P15-19, I5).
CREATE TABLE sm_data.agent_identity (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    environment_id uuid NOT NULL,                    -- the CP environment the enrolment named; opaque here (I7)
    -- Copied from the enrolment record at enrolment, as the envelope is (BE-D71 step 2): OR-27 registers instances "into the
    -- environment and solution the agent was enrolled into", the DP cannot read the CP (I7), and instance_state needs all three
    -- NOT NULL. (Review R-3.)
    catalog_solution_id uuid NOT NULL,
    solution text NOT NULL CONSTRAINT agent_identity_solution CHECK (solution ~ '^[a-z][a-z0-9-]{0,63}$'),
    declared_package_version text NOT NULL CONSTRAINT agent_identity_package_version CHECK (length(declared_package_version) BETWEEN 1 AND 64),
    target_id uuid NOT NULL,
    certificate_thumbprint text NOT NULL
        CONSTRAINT agent_identity_thumbprint CHECK (certificate_thumbprint ~ '^[0-9a-f]{64}$'),
    -- > 0, as the CP's: an epoch created at 0 against the CP's 1 refused every lease as stale (AGW-D17 item 4). (Review R-14.)
    agent_epoch bigint NOT NULL CONSTRAINT agent_identity_agent_epoch CHECK (agent_epoch > 0),
    fleet_epoch bigint NOT NULL CONSTRAINT agent_identity_fleet_epoch CHECK (fleet_epoch > 0),
    -- Revocation is sticky (AGW-D19 item 4): set once, never cleared (trigger below); a fresh enrolment is the remedy.
    revoked_at timestamptz NULL,
    capabilities text[] NOT NULL DEFAULT '{}'
        CONSTRAINT agent_identity_capabilities CHECK (capabilities <@ ARRAY['apply-config', 'lifecycle', 'report-only']::text[]),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, agent_id),
    CONSTRAINT agent_identity_agent_id_global UNIQUE (agent_id),
    CONSTRAINT agent_identity_thumbprint_global UNIQUE (certificate_thumbprint),
    -- The identity names an AGENT target that names THIS agent: the three-column reference matches only a target row whose
    -- agent_id is this agent's, so an SSH target (agent_id NULL) or another agent's target is refused (23503), and with the
    -- deferred target_connection_has_agent below the binding is 1:1. R-13's own-target trigger depends on this.
    CONSTRAINT agent_identity_has_target FOREIGN KEY (tenant_id, target_id, agent_id)
        REFERENCES sm_data.target_connection (tenant_id, target_id, agent_id));
-- The agent target row is inserted first (it carries agent_id), the identity second, in ONE transaction (T3.5): the
-- reverse reference is therefore deferred, so neither row can be left without the other (an orphan agent target is a
-- crash the BE-D32 sweep cleans, not a state the database allows to commit).
ALTER TABLE sm_data.target_connection ADD CONSTRAINT target_connection_has_agent
    FOREIGN KEY (tenant_id, agent_id) REFERENCES sm_data.agent_identity (tenant_id, agent_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION sm_data.agent_identity_revocation_is_sticky() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'an agent revocation is sticky: revoked_at is set once and never cleared or moved'
            USING ERRCODE = 'check_violation', COLUMN = 'revoked_at';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_identity_revocation BEFORE UPDATE ON sm_data.agent_identity
    FOR EACH ROW EXECUTE FUNCTION sm_data.agent_identity_revocation_is_sticky();

-- ---- 3. the session: one row per agent, the fencing epoch monotonic while the row lives (AGW-D3) --------------------
-- "Monotonic" is enforced, not only commented (T1.1 review, finding 5): the runtime role holds UPDATE on fencing_epoch.
CREATE TABLE sm_data.agent_session (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    fencing_epoch bigint NOT NULL CONSTRAINT agent_session_epoch CHECK (fencing_epoch > 0),
    protocol_version integer NOT NULL CONSTRAINT agent_session_protocol CHECK (protocol_version > 0),
    agent_version text NOT NULL CONSTRAINT agent_session_version CHECK (length(agent_version) BETWEEN 1 AND 64),
    opened_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    closed_at timestamptz NULL,
    PRIMARY KEY (tenant_id, agent_id),
    CONSTRAINT agent_session_has_identity FOREIGN KEY (tenant_id, agent_id)
        REFERENCES sm_data.agent_identity (tenant_id, agent_id));
CREATE FUNCTION sm_data.agent_session_epoch_only_rises() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.fencing_epoch < OLD.fencing_epoch THEN
        RAISE EXCEPTION 'an agent fencing epoch only rises while its session row lives'
            USING ERRCODE = 'check_violation', COLUMN = 'fencing_epoch';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_session_epoch BEFORE UPDATE ON sm_data.agent_session
    FOR EACH ROW EXECUTE FUNCTION sm_data.agent_session_epoch_only_rises();

-- ---- 4. the lease: the CP issues, the DP records (AGW-D24) --------------------------------------------------------
-- A history, not a single row: one ACTIVE lease per agent (partial unique index), the rest EXPIRED / SUPERSEDED /
-- REVOKED. The renewal nonce is a bearer secret for one renewal, so only its digest is stored (I6).
CREATE TABLE sm_data.agent_lease (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    lease_id uuid NOT NULL,
    certificate_thumbprint text NOT NULL CONSTRAINT agent_lease_thumbprint CHECK (certificate_thumbprint ~ '^[0-9a-f]{64}$'),
    agent_epoch bigint NOT NULL,
    fleet_epoch bigint NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    audience text NOT NULL CONSTRAINT agent_lease_audience CHECK (length(audience) BETWEEN 1 AND 128),
    -- lowercase hex of a SHA-256 (text, not bytea: the schema test rejects every bytea column in agent_%). (Review R-5.)
    renewal_nonce_digest text NOT NULL CONSTRAINT agent_lease_nonce_digest CHECK (renewal_nonce_digest ~ '^[0-9a-f]{64}$'),
    capabilities text[] NOT NULL DEFAULT '{}'
        CONSTRAINT agent_lease_capabilities CHECK (capabilities <@ ARRAY['apply-config', 'lifecycle', 'report-only']::text[]),
    placement_ids uuid[] NOT NULL DEFAULT '{}'
        CONSTRAINT agent_lease_placements CHECK (cardinality(placement_ids) <= 64 AND array_position(placement_ids, NULL) IS NULL),
    -- The one free-shape column: an object (never a scalar, an array or a stray rendered string) of at most 4096 bytes.
    verb_ceilings jsonb NOT NULL DEFAULT '{}'
        CONSTRAINT agent_lease_verb_ceilings CHECK (jsonb_typeof(verb_ceilings) = 'object' AND pg_column_size(verb_ceilings) <= 4096),
    admission_margin_ms bigint NOT NULL CONSTRAINT agent_lease_margin CHECK (admission_margin_ms >= 0),
    server_clock timestamptz NOT NULL,
    state text NOT NULL CONSTRAINT agent_lease_state CHECK (state IN ('ACTIVE', 'EXPIRED', 'SUPERSEDED', 'REVOKED')),
    PRIMARY KEY (tenant_id, agent_id, lease_id),
    CONSTRAINT agent_lease_window CHECK (expires_at > issued_at),
    CONSTRAINT agent_lease_nonce_once UNIQUE (renewal_nonce_digest),
    CONSTRAINT agent_lease_has_identity FOREIGN KEY (tenant_id, agent_id)
        REFERENCES sm_data.agent_identity (tenant_id, agent_id));
CREATE UNIQUE INDEX agent_lease_one_active ON sm_data.agent_lease (tenant_id, agent_id) WHERE state = 'ACTIVE';

-- The referenced key for agent_command's composite reference. intent_id is the primary key, so this adds no restriction.
ALTER TABLE sm_data.apply_step_intent ADD CONSTRAINT apply_step_intent_command_key
    UNIQUE (tenant_id, apply_id, intent_id, idempotency_key, placement_id);

-- ---- 5. the command: keyed on the apply journal's ATTEMPT-FREE intent key (BE-D66, P15-3) -------------------------
CREATE TABLE sm_data.agent_command (
    tenant_id uuid NOT NULL,
    command_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    apply_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    -- = apply_step_intent.idempotency_key (applyId:step:replica). NEVER contains the attempt: a resumed apply re-enters
    -- with the same key and so finds the same command, which is what makes a RESTART run exactly once.
    intent_key text NOT NULL,
    attempt_id uuid NULL,                            -- the latest attempt; correlation only, never identity
    operation text NOT NULL
        CONSTRAINT agent_command_operation CHECK (operation IN ('PREFLIGHT', 'APPLY', 'REMATERIALIZE', 'RESTART', 'STOP')),
    -- Allocated ONCE per command row: max + 1 per (agent, placement) (AGW-D17 item 1). The agent's journal refuses any
    -- generation below the highest it has seen, so two commands of one placement can never share a number.
    wire_generation bigint NOT NULL CONSTRAINT agent_command_generation CHECK (wire_generation > 0),
    -- The KEYED hash only (OR-32). A plain SHA-256 exists on the wire and in memory and is never stored. The version spelling is
    -- HmacConfigHasher.SPELLING's: v1..v999999999, no leading zero.
    config_hash text NULL CONSTRAINT agent_command_config_hash CHECK (config_hash ~ '^hmacsha256v[1-9][0-9]{0,8}:[0-9a-f]{64}$'),
    state text NOT NULL
        CONSTRAINT agent_command_state CHECK (state IN ('QUEUED', 'OFFERED', 'DELIVERED', 'ACKED', 'REFUSED', 'SUPERSEDED')),
    outcome text NULL
        CONSTRAINT agent_command_outcome CHECK (outcome IN ('APPLIED', 'FAILED_NO_EFFECT', 'FAILED_ROLLED_BACK',
            'SUPERSEDED', 'REFUSED', 'APPLY_INDETERMINATE', 'PREFLIGHT_OK', 'PREFLIGHT_REFUSED')),
    detail_code text NULL CONSTRAINT agent_command_detail CHECK (detail_code ~ '^[A-Za-z][A-Za-z0-9._]{0,63}$'),
    offered_epoch bigint NULL,
    received_request_id text NULL CONSTRAINT agent_command_received_rid CHECK (length(received_request_id) BETWEEN 1 AND 64),
    ack_request_id text NULL CONSTRAINT agent_command_ack_rid CHECK (length(ack_request_id) BETWEEN 1 AND 64),
    deadline timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    notified_at timestamptz NULL,
    received_at timestamptz NULL,
    acked_at timestamptz NULL,
    PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT agent_command_hash_on_config CHECK ((operation IN ('APPLY', 'REMATERIALIZE')) = (config_hash IS NOT NULL)),
    CONSTRAINT agent_command_outcome_when_final CHECK ((state IN ('ACKED', 'REFUSED')) = (outcome IS NOT NULL)),
    CONSTRAINT agent_command_generation_once UNIQUE (tenant_id, agent_id, placement_id, wire_generation),
    CONSTRAINT agent_command_has_identity FOREIGN KEY (tenant_id, agent_id)
        REFERENCES sm_data.agent_identity (tenant_id, agent_id),
    CONSTRAINT agent_command_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id),
    -- ONE composite reference, so a row cannot name one intent by id and another by key, nor another tenant's, nor a placement
    -- other than the intent's own (V021 made the intent placement-aware). (Review R-13, G-6 (i).)
    CONSTRAINT agent_command_has_intent FOREIGN KEY (tenant_id, apply_id, intent_id, intent_key, placement_id)
        REFERENCES sm_data.apply_step_intent (tenant_id, apply_id, intent_id, idempotency_key, placement_id));
-- One LIVE command per (intent key, operation): enqueue returns the existing one instead of minting another (BE-D66).
-- The operation is part of the identity because rematerializeIfCurrent reuses the MATERIALIZE intent (ApplyExecutor), so an
-- APPLY and a REMATERIALIZE of one intent are different commands. (Review R-6.)
CREATE UNIQUE INDEX agent_command_one_live_per_intent ON sm_data.agent_command (tenant_id, intent_key, operation)
    WHERE state IN ('QUEUED', 'OFFERED', 'DELIVERED');
-- A command addresses a placement ON THE AGENT'S OWN TARGET, whoever wrote the row. (Review R-13.)
CREATE FUNCTION sm_data.agent_command_placement_on_agents_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sm_data.placement p
                     JOIN sm_data.agent_identity a ON a.tenant_id = p.tenant_id AND a.target_id = p.target_id
                    WHERE p.tenant_id = NEW.tenant_id AND p.placement_id = NEW.placement_id AND a.agent_id = NEW.agent_id) THEN
        RAISE EXCEPTION 'an agent command must address a placement on the agent''s own target'
            USING ERRCODE = 'check_violation', COLUMN = 'placement_id';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_command_placement BEFORE INSERT ON sm_data.agent_command
    FOR EACH ROW EXECUTE FUNCTION sm_data.agent_command_placement_on_agents_target();
CREATE INDEX agent_command_poll ON sm_data.agent_command (tenant_id, agent_id, state, deadline);

-- ---- 6. report intake watermarks (AGW-D28) ------------------------------------------------------------------------
-- One replica per placement (AGW-D25), so (agent, placement) is the reporter's identity.
CREATE TABLE sm_data.agent_report_watermark (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    last_sequence bigint NOT NULL CONSTRAINT agent_report_watermark_seq CHECK (last_sequence >= 0),
    last_item_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, agent_id, placement_id),
    CONSTRAINT agent_report_watermark_has_identity FOREIGN KEY (tenant_id, agent_id)
        REFERENCES sm_data.agent_identity (tenant_id, agent_id),
    CONSTRAINT agent_report_watermark_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id));

CREATE TABLE sm_data.agent_telemetry_watermark (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    stream_id text NOT NULL CONSTRAINT agent_telemetry_stream CHECK (stream_id ~ '^[A-Za-z0-9._-]{1,128}$'),
    last_batch_seq bigint NOT NULL CONSTRAINT agent_telemetry_seq CHECK (last_batch_seq >= 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, agent_id, stream_id),
    CONSTRAINT agent_telemetry_watermark_has_identity FOREIGN KEY (tenant_id, agent_id)
        REFERENCES sm_data.agent_identity (tenant_id, agent_id));

-- ---- 7. OR-27 discovery with tombstones (AGW-D30, P15-9) -----------------------------------------------------------
-- Keyed (agent, label, container name). REFUSED / ABANDONED / UNDECLARED / RETIRED are TERMINAL: a re-report of the same
-- key answers the stored state and mints nothing. The state is updated only by the DP's own transactions (undeclare,
-- retire, the BE-D32 sweep, a release rule); the agent supplies facts, never a state.
CREATE TABLE sm_data.agent_discovery (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    instance_label text NOT NULL CONSTRAINT agent_discovery_label CHECK (length(instance_label) BETWEEN 1 AND 128),
    container_name text NOT NULL
        CONSTRAINT agent_discovery_container_name CHECK (container_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    container_id text NOT NULL CONSTRAINT agent_discovery_container_id CHECK (container_id ~ '^[0-9a-f]{12,64}$'),
    state text NOT NULL
        CONSTRAINT agent_discovery_state CHECK (state IN ('PENDING', 'REGISTERED', 'REFUSED', 'ABANDONED', 'UNDECLARED', 'RETIRED')),
    instance_id uuid NULL,
    placement_id uuid NULL,
    refusal_code text NULL CONSTRAINT agent_discovery_refusal CHECK (refusal_code ~ '^[a-z][a-z0-9.-]{0,63}$'),
    first_seen_at timestamptz NOT NULL,
    settled_at timestamptz NULL,
    PRIMARY KEY (tenant_id, agent_id, instance_label, container_name),
    CONSTRAINT agent_discovery_registered_has_ids CHECK
        (state NOT IN ('REGISTERED', 'UNDECLARED', 'RETIRED') OR (instance_id IS NOT NULL AND placement_id IS NOT NULL)),
    CONSTRAINT agent_discovery_refused_has_code CHECK ((state = 'REFUSED') = (refusal_code IS NOT NULL)),
    CONSTRAINT agent_discovery_settled CHECK ((state = 'PENDING') = (settled_at IS NULL)),
    CONSTRAINT agent_discovery_has_identity FOREIGN KEY (tenant_id, agent_id)
        REFERENCES sm_data.agent_identity (tenant_id, agent_id),
    CONSTRAINT agent_discovery_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id),
    CONSTRAINT agent_discovery_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id));
-- A terminal state is final, for every writer (T1.1 review, finding 5): the runtime role holds UPDATE on state, and the
-- tombstone's whole contract is that a re-report cannot reopen it. Other facts of a terminal row (container_id) may still move.
CREATE FUNCTION sm_data.agent_discovery_terminal_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('REFUSED', 'ABANDONED', 'UNDECLARED', 'RETIRED') AND NEW.state IS DISTINCT FROM OLD.state THEN
        RAISE EXCEPTION 'a terminal agent discovery state (REFUSED, ABANDONED, UNDECLARED, RETIRED) is final'
            USING ERRCODE = 'check_violation', COLUMN = 'state';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_discovery_terminal BEFORE UPDATE ON sm_data.agent_discovery
    FOR EACH ROW EXECUTE FUNCTION sm_data.agent_discovery_terminal_is_final();

-- ---- 8. agent_event: the DP's audit of agent-caused acts (AGW-D29; OR-126 item 45) ---------------------------------
-- Append-only for the runtime role. The CP's change_audit is the audit of governance writes; this is the audit of
-- gateway decisions (refusals, drains, revocations, discovery outcomes) that write no governance row.
CREATE TABLE sm_data.agent_event (
    tenant_id uuid NOT NULL,
    event_id uuid NOT NULL,
    agent_id uuid NULL,                              -- NULL only for a tenant-level event; a refusal before any identity resolved has no tenant and is a metric, not a row (AGW-D29)
    request_id text NOT NULL CONSTRAINT agent_event_request_id CHECK (length(request_id) BETWEEN 1 AND 128),
    code text NOT NULL
        CONSTRAINT agent_event_code CHECK (code IN ('gateway.refusal', 'gateway.command.refused', 'gateway.drain',
            'gateway.drain.complete', 'gateway.revoked', 'agent.enrolled', 'agent.report.refused',
            'agent.telemetry.rejected', 'agent.discovery.registered', 'agent.discovery.refused', 'agent.discovery.settled')),
    actor_kind text NOT NULL CONSTRAINT agent_event_actor_kind CHECK (actor_kind IN ('AGENT', 'PRINCIPAL', 'SYSTEM')),
    actor_id uuid NULL,
    correlation_id uuid NOT NULL,
    client_address text NOT NULL CONSTRAINT agent_event_client_address CHECK (client_address ~ '^[A-Za-z0-9:._%-]{1,64}$'),
    detail_code text NULL CONSTRAINT agent_event_detail CHECK (detail_code ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT agent_event_request_once UNIQUE (tenant_id, request_id),
    CONSTRAINT agent_event_actor_present CHECK ((actor_kind = 'SYSTEM') = (actor_id IS NULL)));
CREATE INDEX agent_event_by_agent ON sm_data.agent_event (tenant_id, agent_id, occurred_at);

-- ---- 9. grants: per-table, narrow; nothing is deletable by the runtime role -------------------------------------
-- (V001's default privileges already give sm_dp_runtime SELECT on every new table.) Immutable columns appear in no
-- UPDATE list: agent_command's identity (placement, apply, intent, intent_key, operation, wire_generation, config_hash)
-- cannot change after enqueue; agent_identity's tenant, target, thumbprint and environment cannot change.
GRANT INSERT ON sm_data.agent_identity TO sm_dp_runtime;
GRANT UPDATE (agent_epoch, fleet_epoch, revoked_at, capabilities) ON sm_data.agent_identity TO sm_dp_runtime;
GRANT INSERT ON sm_data.agent_session TO sm_dp_runtime;
GRANT UPDATE (fencing_epoch, protocol_version, agent_version, opened_at, last_seen_at, closed_at) ON sm_data.agent_session TO sm_dp_runtime;
GRANT INSERT ON sm_data.agent_lease TO sm_dp_runtime;
GRANT UPDATE (state) ON sm_data.agent_lease TO sm_dp_runtime;
GRANT INSERT ON sm_data.agent_command TO sm_dp_runtime;
GRANT UPDATE (attempt_id, state, outcome, detail_code, offered_epoch, received_request_id, ack_request_id, deadline,
              notified_at, received_at, acked_at) ON sm_data.agent_command TO sm_dp_runtime;
GRANT INSERT ON sm_data.agent_report_watermark, sm_data.agent_telemetry_watermark TO sm_dp_runtime;
GRANT UPDATE (last_sequence, last_item_id, updated_at) ON sm_data.agent_report_watermark TO sm_dp_runtime;
GRANT UPDATE (last_batch_seq, updated_at) ON sm_data.agent_telemetry_watermark TO sm_dp_runtime;
GRANT INSERT ON sm_data.agent_discovery TO sm_dp_runtime;
GRANT UPDATE (container_id, state, instance_id, placement_id, refusal_code, settled_at) ON sm_data.agent_discovery TO sm_dp_runtime;
GRANT INSERT ON sm_data.agent_event TO sm_dp_runtime;                 -- SELECT, INSERT only: append-only


-- ==== VDB ============================================================================================================
-- sm_data: agent retention, the OR-77 shape (plan 015 T1.1 / T4.5; AGW-D31; central item 47; OR-130 F-5; OR-134). Number V029
-- (measured 2026-10-09 on 2568862f: V028 is the agent gateway's state).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so the function is born owned by sm_dp_owner.
--
-- Owned by sm_dp_owner, SECURITY DEFINER, no arguments, a pinned search_path ending in pg_temp (PostgreSQL 14, CREATE FUNCTION,
-- "Writing SECURITY DEFINER Functions Safely", fetched 2026-10-09: "the temporary-table schema ... is searched first by default
-- ... write pg_temp as the last entry in search_path"; R-22), every catalog relation schema-qualified, PUBLIC revoked and EXECUTE
-- granted to the runtime role in this same transactional migration. The retention interval is a constant INSIDE the function, so no
-- caller input reaches a DELETE (OR-77's narrow I5 carve-out, extended by name to agent_command retention by OR-130 F-5).
--
-- WHAT IT DELETES: agent_command rows in a final state (ACKED / REFUSED / SUPERSEDED) older than 30 days, EXCEPT the highest
-- wire_generation of each (agent, placement) -- the agent's journal refuses any generation below the highest it has seen and
-- replays the stored outcome of one it holds, so a generation number must never be reused (review R-1).
--
-- ALSO DELETES (OR-134, F-5 scope; plan 015 section 10 item 18): agent_lease rows whose state is not ACTIVE and whose expires_at
-- is more than 30 days ago (a row is written on every lease issue and renewal). An ACTIVE lease is never deleted, however old.
--
-- WHAT IT NEVER DELETES: a live command; agent_event (OR-130 F-5 PERMITS its retention, but AGW-D31 -- "permitted is not
-- required" -- does not ask for it: this function never deletes it); agent_identity; agent_discovery (a tombstone is the
-- contract that stops re-registration); and agent_session: OR-134 keeps sessions, because there is one row per agent and keeping
-- it keeps the fencing epoch monotonic for the agent's whole life (the draft's session DELETE is removed, not held back).
--
-- LOCK CLASS: CREATE FUNCTION and GRANT lock no table. Each RUN of the function takes ROW EXCLUSIVE on sm_data.agent_command
-- and sm_data.agent_lease for its DELETEs, under lock_timeout = 5s and a non-blocking advisory lock (a concurrent run is a no-op).
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). The function holds no data and
-- nothing references it, so by hand and at any time: DROP FUNCTION sm_data.agent_gateway_maintenance(); then delete the V029
-- row from flyway_schema_history. Rows it already deleted are not restored.
CREATE FUNCTION sm_data.agent_gateway_maintenance() RETURNS TABLE (commands_deleted bigint, leases_deleted bigint)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = sm_data, pg_temp
    SET lock_timeout = '5s'
AS $$
DECLARE
    nc bigint;
    nl bigint;
BEGIN
    IF NOT pg_try_advisory_xact_lock(hashtextextended('sm.agent-gateway-maintenance', 0)) THEN
        RETURN QUERY SELECT 0::bigint, 0::bigint;                           -- a concurrent run holds it: a no-op, never a race
        RETURN;
    END IF;
    DELETE FROM sm_data.agent_command c
        WHERE c.state IN ('ACKED', 'REFUSED', 'SUPERSEDED')
          AND coalesce(c.acked_at, c.created_at) < now() - interval '30 days'
          AND EXISTS (SELECT 1 FROM sm_data.agent_command n
                       WHERE n.tenant_id = c.tenant_id AND n.agent_id = c.agent_id AND n.placement_id = c.placement_id
                         AND n.wire_generation > c.wire_generation);
    GET DIAGNOSTICS nc = ROW_COUNT;
    DELETE FROM sm_data.agent_lease
        WHERE state <> 'ACTIVE' AND expires_at < now() - interval '30 days';
    GET DIAGNOSTICS nl = ROW_COUNT;
    RETURN QUERY SELECT nc, nl;
END $$;
REVOKE ALL ON FUNCTION sm_data.agent_gateway_maintenance() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sm_data.agent_gateway_maintenance() TO sm_dp_runtime;

-- ==== VCA ============================================================================================================
-- sm_control: scoped agent registration with the target envelope; the audit function learns the agent actor; catalog
-- rows 44-47 (plan 015 T1.3; OR-126 A8, OR-128 (4) (5); BE-D70, BE-D71, AGW-D24). Number V021 (measured 2026-10-09 on 2568862f: V019 = OR-125 (4) username-per-organization, V020 = OR-131 Q1 operator rename).
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

-- ---- 3. rows 44-47 of the permission catalog (OR-126 A8; levels and scope: OR-128 (4)) ----------------------------------
-- The catalog is the permission_catalog table (V003/V014/V016). The counts after this migration: 47 / Operator 26 / Viewer 9.
INSERT INTO sm_control.permission_catalog (permission_id) VALUES
    ('agent.enrol'),      -- row 44, HIGH, environment scope: issue an enrolment token (also needs target.create)
    ('agent.revoke'),     -- row 45, HIGH, environment scope: revoke an agent
    ('agent.drain'),      -- row 46, STANDARD, environment scope: ask an agent to drain and reconnect
    ('agent.view');       -- row 47, read-only, environment scope
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
