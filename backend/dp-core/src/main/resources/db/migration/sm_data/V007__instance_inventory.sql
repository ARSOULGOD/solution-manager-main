-- PLAN 010 T2.1 — the DP inventory substrate: ONE stateVersion sequence (OR-35), instance_state (carrying the
-- per-instance acquisition mode, OR-71), target_connection (OR-28) and the immutable placement registry (OR-29).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by
-- sm_dp_owner. Every table is keyed by tenant_id first (I5); CP ids (environment, catalog solution, creator) are
-- opaque uuids with NO foreign key across the plane boundary (I7).

-- ---- the one sequence ---------------------------------------------------------------------------------------
-- CACHE 1 is stated, not left to the default, because it is load-bearing: a cached sequence preallocates a block
-- PER SESSION, so two sessions would draw out of order and a later committer could carry a LOWER stamp than an
-- earlier one — exactly what a `sinceStateVersion` backfill must never see (OR-35's commit-order rule).
-- M5 (measured on PostgreSQL 14, as sm_dp_runtime, recorded in plan 010's Measured facts):
--   * a BEFORE trigger may assign a column the invoking role holds NO column privilege on, and may do so on an
--     INSERT that omits the column (the trigger runs before the NOT NULL check);
--   * nextval() inside a plain (non-SECURITY DEFINER) trigger runs as the invoking role and needs USAGE on the
--     sequence (42501 "permission denied for sequence" without it);
--   * an explicit value for a column without an INSERT/UPDATE column grant is refused 42501.
-- CHOICE: GRANT USAGE to the runtime role, no SECURITY DEFINER. A definer stamp function would add a privileged
-- code path (pinned search_path and all) to save the runtime role a privilege that can do no harm: the only thing
-- USAGE adds is calling nextval() directly, which burns a value — a gap, and gaps are already legal (a rolled-back
-- transaction burns one). The runtime role still cannot SET a state_version: it has no column grant on it.
CREATE SEQUENCE sm_data.state_version_seq AS bigint MINVALUE 1 NO CYCLE CACHE 1;
GRANT USAGE ON SEQUENCE sm_data.state_version_seq TO sm_dp_runtime;

-- ---- instance_state -----------------------------------------------------------------------------------------
CREATE TABLE sm_data.instance_state (
    tenant_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    catalog_solution_id uuid NOT NULL,
    solution text NOT NULL,
    declared_package_version text NOT NULL,
    name text NOT NULL CONSTRAINT instance_name CHECK (length(name) BETWEEN 1 AND 128),
    lifecycle text NOT NULL CONSTRAINT instance_lifecycle CHECK (lifecycle IN ('pending', 'active', 'abandoned')),
    -- Owner rulings M-Q2/M-Q2b (OR-71): the acquisition mode is chosen PER INSTANCE at declare and followed by both
    -- the health poller and the metrics collector. It lives HERE, not on placement, so it survives a re-target's new
    -- placement. Immutable in v1: it is in no UPDATE grant (changing it later is a future operation). The two values
    -- are the contract's closed vocabulary (console.yaml Acquisition.mode). There is NO address column (OR-82): direct
    -- mode polls the target's registered address (target_connection.host) at the placement's health port and path.
    acquisition_mode text NOT NULL
        CONSTRAINT instance_acquisition_mode CHECK (acquisition_mode IN ('direct-http', 'via-ssh')),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    state_version bigint NOT NULL,
    PRIMARY KEY (tenant_id, instance_id));
-- OR-88: an instance NAME is unique only among PENDING and ACTIVE instances of its environment. A declare the sweep
-- marks 'abandoned' (BE-D32) never completed, so it no longer holds its name and a retried declare under the same
-- name succeeds; abandoned rows are kept (never deleted) and any number may share a name. A partial unique INDEX, not
-- a table constraint (a constraint cannot carry a WHERE); the name stays `instance_name_once`, which the refusal
-- tests and the 23505 mapping look for.
CREATE UNIQUE INDEX instance_name_once ON sm_data.instance_state (tenant_id, environment_id, name)
    WHERE lifecycle <> 'abandoned';

-- ONE FUNCTION PER OWNING TABLE (V003's measured reason: PL/pgSQL resolves every field reference against the firing
-- row's type, so a shared function branching on TG_TABLE_NAME fails 42703). The commit-order rule (OR-35 correction)
-- says every stamping transaction first takes the owning instance_state row lock. On instance_state ITSELF that lock
-- is the row being written: PostgreSQL locks the tuple (and waits for it) BEFORE it calls a BEFORE ROW UPDATE
-- trigger, so a second writer of the same instance blocks until the first commits and only THEN draws its value —
-- within one instance, stamp order is commit order. An INSERT has no earlier row to order against. (An explicit
-- FOR UPDATE here would be inert; the stamp functions of the tables that DO hang off an instance_state row take it
-- explicitly.) Every write stamps: a service that has nothing observable to change does not write.
CREATE FUNCTION sm_data.stamp_instance_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER instance_state_stamp BEFORE INSERT OR UPDATE ON sm_data.instance_state
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_instance_state_version();

-- The lifecycle moves one way: a declared (pending) instance becomes active or is abandoned, and nothing else. The
-- service's 'pending'-conditional UPDATE is the first enforcer; this is the second, for every other writer (S2
-- review: with OR-88 a revived abandoned row would already have its placements retired). A same-value write is not
-- a transition and passes.
CREATE FUNCTION sm_data.instance_lifecycle_moves_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle = 'pending' AND NEW.lifecycle IN ('active', 'abandoned') THEN
        RETURN NEW;
    END IF;
    IF NEW.lifecycle NOT IN ('pending', 'active', 'abandoned') THEN
        RETURN NEW;                                  -- not a state at all: instance_lifecycle (the CHECK) refuses it, by name
    END IF;
    RAISE EXCEPTION 'an instance lifecycle may only move from pending to active or abandoned';
END $$;
CREATE TRIGGER instance_state_lifecycle BEFORE UPDATE ON sm_data.instance_state
    FOR EACH ROW WHEN (OLD.lifecycle IS DISTINCT FROM NEW.lifecycle)
    EXECUTE FUNCTION sm_data.instance_lifecycle_moves_forward();

-- ---- target_connection --------------------------------------------------------------------------------------
-- OR-28: mutable (host, port, user, fingerprint, credential), read fresh per SSH session; the CP keeps only the
-- governance row. The field grammars copy SshDockerPlacement's patterns (two enforcers, the standing pattern).
CREATE TABLE sm_data.target_connection (
    tenant_id uuid NOT NULL,
    target_id uuid NOT NULL,
    transport text NOT NULL CONSTRAINT target_connection_transport CHECK (transport = 'ssh-docker'),
    host text NOT NULL
        CONSTRAINT target_connection_host CHECK (host ~ '^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$'),
    port integer NOT NULL CONSTRAINT target_connection_port CHECK (port BETWEEN 1 AND 65535),
    ssh_user text NOT NULL
        CONSTRAINT target_connection_user CHECK (ssh_user ~ '^[A-Za-z_][A-Za-z0-9_-]{0,31}$'),
    host_key_fingerprint text NOT NULL
        CONSTRAINT target_connection_fp CHECK (host_key_fingerprint ~ '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$'),
    credential_ref uuid NOT NULL,                    -- sm_secret.secret(secret_id); never the key itself (I6)
    -- B2: the envelope a declare body may name paths and containers inside. Set by the registering operator command
    -- (T2.7) and IMMUTABLE — in no UPDATE grant; changing it means registering a new target.
    allowed_config_root text NOT NULL
        CONSTRAINT target_connection_config_root CHECK (
            allowed_config_root ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$' AND strpos(allowed_config_root, '..') = 0),
    allowed_container_prefix text NOT NULL
        CONSTRAINT target_connection_container_prefix CHECK (allowed_container_prefix ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, target_id));
-- M7(a): ONE live target per SSH HOST per ORGANIZATION, not per target row. The pinned host key identifies the host
-- whatever name or port reaches it, so two rows for one VM in one organization (an alias, a second sshd port) are
-- refused, and the per-target indexes on placement below are therefore per-host. PER ORGANIZATION (OR-89, owner
-- ruling 2026-10-05): the index leads with tenant_id, so two organizations may each register the same machine; the
-- accepted consequence is that one host can then be driven by two organizations' configurations. A cloned image
-- sharing a host key inside one organization is refused too, which fails safe.
CREATE UNIQUE INDEX target_connection_host_once ON sm_data.target_connection (tenant_id, host_key_fingerprint)
    WHERE retired_at IS NULL;

-- ---- placement ----------------------------------------------------------------------------------------------
-- OR-29: immutable; a re-target mints a NEW row. Never stamped (OR-35: "never on the immutable placement row").
CREATE TABLE sm_data.placement (
    tenant_id uuid NOT NULL,
    placement_id uuid NOT NULL
        CONSTRAINT placement_not_sentinel CHECK (placement_id <> '00000000-0000-0000-0000-000000000000'),
                                                     -- the nil UUID is the apply journal's instance-wide sentinel
    instance_id uuid NOT NULL,
    target_id uuid NOT NULL,
    container_name text NOT NULL
        CONSTRAINT placement_container_name CHECK (container_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    remote_config_directory text NOT NULL
        CONSTRAINT placement_config_directory CHECK (
            remote_config_directory ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$'
            AND strpos(remote_config_directory, '..') = 0),
    container_config_path text NOT NULL
        CONSTRAINT placement_container_config_path CHECK (
            container_config_path ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$'
            AND strpos(container_config_path, '..') = 0),
    file_name text NOT NULL
        CONSTRAINT placement_file_name CHECK (file_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    mechanism text NOT NULL
        CONSTRAINT placement_mechanism CHECK (mechanism IN ('ADDITIONAL_LOCATION', 'OVERWRITE_IN_PLACE')),
                                                     -- ConfigLocation.Mechanism's constant names (SM-D3)
    retained_generations integer NOT NULL
        CONSTRAINT placement_retained_generations CHECK (retained_generations >= 1),
    -- M8: the probe is declared per placement (OR-29), copied at declare from the package's health.probe. Only the two
    -- HTTP kinds are probable over SSH/HTTP; a package declaring neither stores NULLs and its health reads NOTFOUND
    -- not_exposed (T7.2), never a guess.
    health_kind text NULL
        CONSTRAINT placement_health_kind CHECK (health_kind IN ('actuator-health', 'http-status')),
    health_path text NULL
        CONSTRAINT placement_health_path CHECK (health_path ~ '^/[A-Za-z0-9/_.-]{0,255}$'),
    health_port integer NULL
        CONSTRAINT placement_health_port CHECK (health_port BETWEEN 1 AND 65535),     -- the HOST-side port (M12)
    -- Owner ruling 2026-09-29 (S0-10): the package's declared probe strength, copied at declare like kind and path —
    -- what a rolling-health-gated gate can prove on this placement. Never inferred, never Cache-coded.
    health_strength text NULL
        CONSTRAINT placement_health_strength CHECK (health_strength IN ('readiness', 'liveness')),
    CONSTRAINT health_probe_complete CHECK (
        (health_kind IS NULL) = (health_path IS NULL)
        AND (health_kind IS NULL) = (health_port IS NULL)
        AND (health_kind IS NULL) = (health_strength IS NULL)),
    -- (no per-placement acquisition: owner rulings M-Q2/M-Q2b, OR-71, put it on instance_state)
    created_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, placement_id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.instance_state (tenant_id, instance_id),
    FOREIGN KEY (tenant_id, target_id) REFERENCES sm_data.target_connection (tenant_id, target_id));
-- OR-29: per-target (= per-host, above) uniqueness of what an apply WRITES, what a stop ADDRESSES and what a probe
-- READS, among LIVE placements. The config FILE needs no index of its own: file = dir + '/' + name, so a unique
-- directory already makes the file unique (M7(b): a (dir, file) index could never fire on its own).
CREATE UNIQUE INDEX placement_container_once ON sm_data.placement (tenant_id, target_id, container_name)
    WHERE retired_at IS NULL;
CREATE UNIQUE INDEX placement_config_dir_once ON sm_data.placement (tenant_id, target_id, remote_config_directory)
    WHERE retired_at IS NULL;
-- M7(d): two live placements probing one host port would each read the other's health.
CREATE UNIQUE INDEX placement_health_port_once ON sm_data.placement (tenant_id, target_id, health_port)
    WHERE retired_at IS NULL;

-- Immutable except retirement: the one legal UPDATE sets retired_at NULL -> a value and changes nothing else.
-- A trigger, not only the column grant: the grant guards the runtime role, this guards every other writer.
CREATE FUNCTION sm_data.placement_immutable_except_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.retired_at IS NOT NULL
       OR NEW.retired_at IS NULL
       OR (to_jsonb(NEW) - 'retired_at') IS DISTINCT FROM (to_jsonb(OLD) - 'retired_at') THEN
        RAISE EXCEPTION 'a placement is immutable: the only legal change is retiring it once';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER placement_immutable BEFORE UPDATE ON sm_data.placement
    FOR EACH ROW EXECUTE FUNCTION sm_data.placement_immutable_except_retirement();

-- B2, the SECOND of three enforcers (the service checks first, T2.5; the adapter's SFTP-canonicalised preflight
-- checks last, T6.1). Reads the placement's target_connection row — same tenant, same schema, no cross-schema read —
-- and refuses (SQLSTATE 23514, naming the FIELD, never the value) a container outside allowed_container_prefix or a
-- directory that is neither allowed_config_root nor beneath it. starts_with() and an explicit '/' boundary, never
-- LIKE: '_' in a token is a LIKE wildcard and a bare prefix match lets /srv/sm-evil through root /srv/sm.
CREATE FUNCTION sm_data.placement_inside_target_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    t sm_data.target_connection%ROWTYPE;
BEGIN
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
CREATE TRIGGER placement_envelope BEFORE INSERT ON sm_data.placement
    FOR EACH ROW EXECUTE FUNCTION sm_data.placement_inside_target_envelope();

-- The converse (S2 review): a target is not retired while a live placement still sits on it, or a fresh
-- registration of the same host could collide with that placement's container, directory and health port. Names no row.
CREATE FUNCTION sm_data.target_not_retired_under_live_placements() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.retired_at IS NULL AND NEW.retired_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM sm_data.placement
            WHERE tenant_id = OLD.tenant_id AND target_id = OLD.target_id AND retired_at IS NULL) THEN
        RAISE EXCEPTION 'a target cannot be retired while it has live placements';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER target_connection_retire_guard BEFORE UPDATE ON sm_data.target_connection
    FOR EACH ROW EXECUTE FUNCTION sm_data.target_not_retired_under_live_placements();

-- ---- grants (per-table; nothing is deletable) ----------------------------------------------------------------
GRANT SELECT,
      INSERT (tenant_id, instance_id, environment_id, catalog_solution_id, solution, declared_package_version,
              name, lifecycle, acquisition_mode, created_by, created_at),
      UPDATE (lifecycle)
    ON sm_data.instance_state TO sm_dp_runtime;
    -- INSERT is column-scoped to EVERYTHING BUT state_version, and UPDATE to lifecycle alone: the trigger sets the
    -- stamp (an explicit value is refused 42501, measured), and identity, the declared package version and the
    -- acquisition mode (OR-71) are not runtime-writable after declare.
GRANT SELECT, INSERT ON sm_data.placement TO sm_dp_runtime;
GRANT UPDATE (retired_at) ON sm_data.placement TO sm_dp_runtime;
GRANT SELECT, INSERT ON sm_data.target_connection TO sm_dp_runtime;
GRANT UPDATE (host, port, ssh_user, host_key_fingerprint, credential_ref, retired_at)
    ON sm_data.target_connection TO sm_dp_runtime;
    -- NOT allowed_config_root / allowed_container_prefix: the envelope is immutable.
