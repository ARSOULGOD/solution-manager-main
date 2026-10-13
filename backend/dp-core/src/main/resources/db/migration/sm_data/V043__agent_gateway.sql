-- sm_data: the agent gateway's state, over OUR placement and target_connection tables (plan 015 T1.1; BE-D68, BE-D71,
-- AGW-D24..AGW-D30). Number V028 (measured 2026-10-09 on 2568862f: sm_data's latest is V027);
-- RENUMBERED V043 by the reconciliation into UAT (OR-147, 2026-10-12), after UAT's Connections migrations V037-V042 (the old
-- agent's V028-V036 were removed). Content unchanged; every self-reference below names the new numbers (V043-V046).
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
-- (without the NULL guard) by hand; finally delete the V043 row from flyway_schema_history.

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
