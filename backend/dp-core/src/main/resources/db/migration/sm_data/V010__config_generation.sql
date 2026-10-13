-- PLAN 010 T5.2 — the config of record (OR-33): immutable, bounded generations, and the two instance_state columns
-- that name where an instance is (last_generation) and how far its latest generation has got (reached_rung, OR-37).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by
-- sm_dp_owner. Keyed by tenant_id first (I5); no foreign key leaves sm_data (I7); no secret value is stored (I6).

-- ---- instance_state: the head of the generation chain ---------------------------------------------------------
-- last_generation is the highest generation the instance has staged (0 before the first), advanced by the stage
-- transaction under the instance_state row lock. reached_rung is the INSTANCE rung (lowest across live placements,
-- OR-37), written by the apply path (T6.4/T6.5); NULL until an apply has reached any rung. Writing either stamps
-- instance_state (V007's trigger fires on every UPDATE), which is the stateVersion instance.config-state carries.
ALTER TABLE sm_data.instance_state
    ADD COLUMN last_generation bigint NOT NULL DEFAULT 0
        CONSTRAINT last_generation_nonneg CHECK (last_generation >= 0);
ALTER TABLE sm_data.instance_state
    ADD COLUMN reached_rung text NULL
        CONSTRAINT reached_rung_known CHECK (
            reached_rung IN ('desired', 'rendered', 'persisted', 'restart-observed', 'effective-verified'));
GRANT UPDATE (last_generation, reached_rung) ON sm_data.instance_state TO sm_dp_runtime;
    -- additive to V007's UPDATE (lifecycle): identity, the declared package version and the acquisition mode remain
    -- not runtime-writable.

-- ---- config_generation ----------------------------------------------------------------------------------------
CREATE TABLE sm_data.config_generation (
    tenant_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    generation bigint NOT NULL CONSTRAINT generation_positive CHECK (generation > 0),
    package_solution text NOT NULL
        CONSTRAINT generation_package_solution CHECK (char_length(package_solution) BETWEEN 1 AND 128),
    package_version text NOT NULL
        CONSTRAINT generation_package_version CHECK (char_length(package_version) BETWEEN 1 AND 128),
    component_selection jsonb NOT NULL
        CONSTRAINT generation_selection_is_object CHECK (jsonb_typeof(component_selection) = 'object'),
    draft_values jsonb NOT NULL
        CONSTRAINT generation_draft_is_object CHECK (jsonb_typeof(draft_values) = 'object'),
    rendered_entries jsonb NOT NULL
        CONSTRAINT generation_rendered_is_array CHECK (jsonb_typeof(rendered_entries) = 'array'),
    -- I6, the database's footing for "a secret is a reference, never a value": no rendered entry may carry both a
    -- plain value and a secretRef, and a secretRef is a UUID, nothing else. (The model type cannot build such an
    -- entry; this is the second enforcer for every other writer.)
    CONSTRAINT rendered_entry_value_xor_secret CHECK (NOT coalesce(jsonb_path_exists(rendered_entries,
        '$[*] ? (exists(@.secretRef) && exists(@.value))', '{}'::jsonb, true), false)),
    CONSTRAINT rendered_secret_ref_is_uuid CHECK (NOT coalesce(jsonb_path_exists(rendered_entries,
        '$[*].secretRef ? (!(@.type() == "string" && @ like_regex "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))',
        '{}'::jsonb, true), false)),
    -- OR-32: keyed over the canonical content (references, never resolved values); drives OR-33 guard 1. Spelt
    -- hmacsha256v<n>:<hex>, which already fits this grammar (the same one the model enforces on every hash).
    content_digest text NOT NULL
        CONSTRAINT generation_digest_grammar CHECK (content_digest ~ '^[a-z0-9]+:[0-9a-f]{16,128}$'),
    origin text NOT NULL
        CONSTRAINT generation_origin_known CHECK (origin IN ('edit', 'rollback', 'restore-runtime', 'accept-runtime')),
    derived_from_generation bigint NULL
        CONSTRAINT generation_derived_positive CHECK (derived_from_generation > 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    state_version bigint NOT NULL,
    PRIMARY KEY (tenant_id, instance_id, generation),
    CONSTRAINT config_generation_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id));

-- The stamp. ONE FUNCTION PER OWNING TABLE (V003's measured reason). Takes the owning instance_state row FOR UPDATE
-- first (the recorded commit-order rule: within one instance, stamp order is commit order), then draws the next
-- value; RAISES when the instance is missing rather than stamping silently. The runtime role has no column grant on
-- state_version, so only this trigger sets it (V007's measured M5).
CREATE FUNCTION sm_data.stamp_config_generation_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM sm_data.instance_state
        WHERE tenant_id = NEW.tenant_id AND instance_id = NEW.instance_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'config_generation names an instance the data plane has not declared'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'config_generation_has_instance';
    END IF;
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER config_generation_stamp BEFORE INSERT ON sm_data.config_generation
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_config_generation_state_version();

-- What must NEVER be deleted, whatever the application's retention configuration says (a trigger cannot read
-- application configuration and must not hard-code N). Two rules, each with its own refusal:
--   (a) the instance's LATEST generation (generation = instance_state.last_generation): a floor of one, so the
--       Designer can always rebuild its draft (OR-34);
--   (b) EVERY generation named by ANY apply_request row of that instance (owner ruling 2026-09-29 (Q-R), OR-50, read
--       literally). In sm_data the apply journal IS the DP audit record for apply, rollback and restart (D10
--       amendment 1.4), so a generation the journal names is audit evidence and is kept.
-- Messages name the rule, never a row or a value (I6).
CREATE FUNCTION sm_data.config_generation_protected() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    head bigint;
BEGIN
    SELECT last_generation INTO head FROM sm_data.instance_state
        WHERE tenant_id = OLD.tenant_id AND instance_id = OLD.instance_id;
    IF head IS NOT NULL AND OLD.generation = head THEN
        RAISE EXCEPTION 'the latest generation of an instance is never deleted';
    END IF;
    IF EXISTS (SELECT 1 FROM sm_data.apply_request
               WHERE tenant_id = OLD.tenant_id AND instance_id = OLD.instance_id AND generation = OLD.generation) THEN
        RAISE EXCEPTION 'a generation named by an apply request is never deleted';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER config_generation_protect BEFORE DELETE ON sm_data.config_generation
    FOR EACH ROW EXECUTE FUNCTION sm_data.config_generation_protected();

-- ---- grants (explicit; the row is immutable) --------------------------------------------------------------------
-- INSERT is column-scoped to everything but state_version (the trigger stamps it). NO UPDATE at all: a generation is
-- immutable. DELETE exists for retention only, and only the trigger above decides what it may remove.
GRANT SELECT, DELETE ON sm_data.config_generation TO sm_dp_runtime;
GRANT INSERT (tenant_id, instance_id, generation, package_solution, package_version, component_selection,
              draft_values, rendered_entries, content_digest, origin, derived_from_generation, created_by, created_at)
    ON sm_data.config_generation TO sm_dp_runtime;
