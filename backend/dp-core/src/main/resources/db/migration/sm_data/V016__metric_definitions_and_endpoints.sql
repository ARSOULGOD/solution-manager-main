-- PLAN 011 P11-2.2 (sm_data; the plan called this V015 and the lane built it as the placeholder V021; renumbered V016 at the
-- wave-2 merge, the next free number behind plan 010's T11.2 and OR-108's V015). Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by
-- sm_dp_owner. Keyed by tenant_id first (I5). No foreign key leaves sm_data (I7): the control plane's catalog solution id
-- is an opaque uuid here, as in V007.
--
-- Three things live here: per-package-version metric definitions, copied ONCE (M-Q5 (a')), the host-side metrics ports of
-- a placement (M-Q14 (a)), and the per-generation active metric list (M-Q15 (a)). All five tables are INSERT-only for the
-- runtime role: a loaded package version is immutable, a placement is immutable, and a generation is born with its list.

-- ==== 1. definitions, copied once per (tenant, solution, version) =====================================================
CREATE TABLE sm_data.package_version_digest (
    tenant_id uuid NOT NULL,
    catalog_solution_id uuid NOT NULL,
    package_version text NOT NULL,
    package_digest text NOT NULL CONSTRAINT package_digest_grammar CHECK (package_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, catalog_solution_id, package_version));

CREATE TABLE sm_data.metric_definition (
    tenant_id uuid NOT NULL,
    catalog_solution_id uuid NOT NULL,
    package_version text NOT NULL,
    metric_name text NOT NULL CONSTRAINT metric_name_grammar CHECK (metric_name ~ '^[a-z][a-z0-9_.]{0,127}$'),
    component_type text NOT NULL,
    metric_type text NOT NULL CONSTRAINT metric_type_known CHECK (metric_type IN ('counter', 'gauge', 'histogram')),
    unit text NOT NULL CONSTRAINT metric_unit_length CHECK (length(unit) BETWEEN 1 AND 64),
    source_kind text NOT NULL CONSTRAINT metric_source_kind_known
        CHECK (source_kind IN ('http_json', 'solace_event', 'prometheus', 'exec', 'jdbc_query')),
    source jsonb NOT NULL,                              -- the grammar-checked source, exactly as P11-1.1 defines it
    interval_seconds integer NULL CONSTRAINT metric_interval_range CHECK (interval_seconds BETWEEN 10 AND 3600),
    default_chart boolean NOT NULL,
    package_digest text NOT NULL,
    PRIMARY KEY (tenant_id, catalog_solution_id, package_version, metric_name),
    CONSTRAINT metric_definition_has_version FOREIGN KEY (tenant_id, catalog_solution_id, package_version)
        REFERENCES sm_data.package_version_digest (tenant_id, catalog_solution_id, package_version));

-- M-Q9 (d') / OQ-1 (a): the restart signal is per component and optional.
CREATE TABLE sm_data.metric_restart_signal (
    tenant_id uuid NOT NULL,
    catalog_solution_id uuid NOT NULL,
    package_version text NOT NULL,
    component_type text NOT NULL,
    source jsonb NOT NULL,
    package_digest text NOT NULL,
    PRIMARY KEY (tenant_id, catalog_solution_id, package_version, component_type),
    CONSTRAINT restart_signal_has_version FOREIGN KEY (tenant_id, catalog_solution_id, package_version)
        REFERENCES sm_data.package_version_digest (tenant_id, catalog_solution_id, package_version));

-- The DATABASE half of "a digest mismatch fails closed" (M-Q5): a child row must carry the digest the version was
-- recorded with, whoever writes it. One function serves both child tables, with no TG_TABLE_NAME branch: both name the
-- same four columns, and PL/pgSQL resolves a field against the firing row's type (V003's measured reason for one function
-- per table was a branch over DIFFERENT fields). An unknown version passes here and is refused, by name, by the foreign key.
-- The message names the class and no digest.
CREATE FUNCTION sm_data.package_digest_matches_recorded() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    recorded text;
BEGIN
    SELECT d.package_digest INTO recorded FROM sm_data.package_version_digest d
        WHERE d.tenant_id = NEW.tenant_id AND d.catalog_solution_id = NEW.catalog_solution_id
          AND d.package_version = NEW.package_version;
    IF FOUND AND recorded IS DISTINCT FROM NEW.package_digest THEN
        RAISE EXCEPTION 'package version content differs'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'package_digest_matches', TABLE = TG_TABLE_NAME;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER metric_definition_digest BEFORE INSERT ON sm_data.metric_definition
    FOR EACH ROW EXECUTE FUNCTION sm_data.package_digest_matches_recorded();
CREATE TRIGGER metric_restart_signal_digest BEFORE INSERT ON sm_data.metric_restart_signal
    FOR EACH ROW EXECUTE FUNCTION sm_data.package_digest_matches_recorded();

-- ==== 2. host-side metrics ports, immutable per placement (M-Q14 (a)) =================================================
-- A re-target mints a NEW placement (V007), and its rows are new rows with the new placement. The composite key lets an
-- endpoint's target be proved to be its placement's target by a foreign key, with no copied column to drift.
ALTER TABLE sm_data.placement ADD CONSTRAINT placement_target_key UNIQUE (tenant_id, placement_id, target_id);

CREATE TABLE sm_data.placement_metrics_endpoint (
    tenant_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    target_id uuid NOT NULL,
    source_port integer NOT NULL CONSTRAINT metrics_source_port_range CHECK (source_port BETWEEN 1 AND 65535),
    host_port integer NOT NULL CONSTRAINT metrics_host_port_range CHECK (host_port BETWEEN 1 AND 65535),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, placement_id, source_port),
    -- One declare may not map two source ports to one host port ({8081: 28081, 9090: 28081} would scrape one of them on
    -- the wrong container port). The within-placement half of M-Q14's "(tenant, target, host_port) unique among live
    -- placements"; the trigger below is the cross-placement half and exempts the placement's own rows.
    CONSTRAINT placement_metrics_host_port_once UNIQUE (tenant_id, placement_id, host_port),
    CONSTRAINT endpoint_has_placement_on_target FOREIGN KEY (tenant_id, placement_id, target_id)
        REFERENCES sm_data.placement (tenant_id, placement_id, target_id));
-- SCOPE: one target, hence one tenant (target_connection is tenant-bound). Under OR-89 two organizations may register the
-- same machine as two targets, and nothing here (or in V007's placement_health_port_once) stops them naming one host
-- port: OQ-23, ruled (a): a runbook rule (P11-4.4), no code.

-- Serialize every declare that names a host port on a target by locking the target's row FIRST. Both triggers below take
-- THE SAME lock, so the second declare waits for the first to commit and then sees its rows (the checks run as separate
-- statements, and in READ COMMITTED each one re-reads). Without it: B inserts its placement (health 28081) and sees no
-- metrics port 28081, A then inserts endpoint 28081 and cannot see B's uncommitted placement, and both commit.
-- Lock order (plan 011 P11-2.2 step 0, measured at V007): instance_state, placement insert, target_connection FOR UPDATE,
-- endpoint inserts. No plan 010 path locked target_connection before this, so there is no inverse order to deadlock with.
-- The runtime role may take the lock: it holds column-level UPDATE on target_connection, which is what FOR UPDATE asks.
-- "Live" = placement.retired_at IS NULL, read at insert time: no copied column, so no UPDATE and no drift (M-Q14).
-- The message names the CLASS, never the other instance or the port (Review Focus 2), and carries no DETAIL.
CREATE FUNCTION sm_data.endpoint_host_port_free_on_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM sm_data.target_connection t
        WHERE t.tenant_id = NEW.tenant_id AND t.target_id = NEW.target_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM sm_data.placement_metrics_endpoint e
                   JOIN sm_data.placement p ON p.tenant_id = e.tenant_id AND p.placement_id = e.placement_id
                WHERE e.tenant_id = NEW.tenant_id AND e.target_id = NEW.target_id AND e.host_port = NEW.host_port
                  AND e.placement_id <> NEW.placement_id AND p.retired_at IS NULL) THEN
        RAISE EXCEPTION 'a live placement on this target already maps that metrics host port'
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'metrics_host_port_in_use', TABLE = TG_TABLE_NAME;
    END IF;
    IF EXISTS (SELECT 1 FROM sm_data.placement p
                WHERE p.tenant_id = NEW.tenant_id AND p.target_id = NEW.target_id AND p.health_port = NEW.host_port
                  AND p.placement_id <> NEW.placement_id AND p.retired_at IS NULL) THEN
        RAISE EXCEPTION 'a live placement on this target already probes its health on that host port'
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'metrics_host_port_is_a_health_port', TABLE = TG_TABLE_NAME;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER placement_metrics_endpoint_port_free BEFORE INSERT ON sm_data.placement_metrics_endpoint
    FOR EACH ROW EXECUTE FUNCTION sm_data.endpoint_host_port_free_on_target();

-- The other direction: a new placement whose health port is another live placement's metrics host port. The lock is taken
-- UNCONDITIONALLY (even for a placement with no probe): the placement's own foreign key takes FOR KEY SHARE on the target's
-- row at the end of the statement, and a transaction that holds only that, then asks for FOR UPDATE at its endpoint insert
-- while another declare queues for the same row, is a deadlock. Taking FOR UPDATE first means a declare never holds the
-- weaker lock while wanting the stronger one.
CREATE FUNCTION sm_data.placement_health_port_free_of_metrics_ports() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM sm_data.target_connection t
        WHERE t.tenant_id = NEW.tenant_id AND t.target_id = NEW.target_id FOR UPDATE;
    IF NEW.health_port IS NOT NULL AND EXISTS (SELECT 1 FROM sm_data.placement_metrics_endpoint e
                   JOIN sm_data.placement p ON p.tenant_id = e.tenant_id AND p.placement_id = e.placement_id
                WHERE e.tenant_id = NEW.tenant_id AND e.target_id = NEW.target_id AND e.host_port = NEW.health_port
                  AND e.placement_id <> NEW.placement_id AND p.retired_at IS NULL) THEN
        RAISE EXCEPTION 'a live placement on this target already maps that host port as a metrics port'
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'health_port_is_a_metrics_port', TABLE = TG_TABLE_NAME;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER placement_health_port_free BEFORE INSERT ON sm_data.placement
    FOR EACH ROW EXECUTE FUNCTION sm_data.placement_health_port_free_of_metrics_ports();

-- ==== 3. the active metric-name list, born with its generation, never edited (M-Q15 (a)) ============================
-- generation 0 = the declare-time set. No foreign key to config_generation: retention (V010) may delete an old
-- generation, and the one the collector uses (the newest that reached restart-observed) is journal-referenced, so
-- config_generation's delete trigger already protects it.
CREATE TABLE sm_data.generation_active_metrics (
    tenant_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    generation bigint NOT NULL CONSTRAINT active_metrics_generation_nonneg CHECK (generation >= 0),
    metric_names text[] NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, instance_id, generation),
    CONSTRAINT active_metrics_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id));

-- ==== grants: nothing here is updatable or deletable ===============================================================
GRANT SELECT, INSERT ON sm_data.package_version_digest, sm_data.metric_definition, sm_data.metric_restart_signal,
      sm_data.placement_metrics_endpoint, sm_data.generation_active_metrics TO sm_dp_runtime;
