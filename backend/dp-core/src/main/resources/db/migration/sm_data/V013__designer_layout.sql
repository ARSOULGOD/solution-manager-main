-- OR-96 (owner, 2026-10-05; UI-D20): the designer's canvas layout is PRESENTATION state, kept in a per-instance record
-- OUTSIDE config_generation, its history and its hash. A layout-only save (the operator moved boxes and changed no
-- configuration) used to store nothing, because generations are immutable and layout is excluded from the content
-- digest; it now updates this record and mints no generation. A config change keeps working as before, and layout never
-- enters `rendered` or the hash.
--
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by
-- sm_dp_owner. Keyed by tenant_id first (I5); no foreign key leaves sm_data (I7); no secret is stored here (I6) - a
-- layout is instance keys and numbers.
--
-- NO stamp trigger, deliberately: the stateVersion stamp (OR-35) is drawn by writes the operator can observe as a
-- change of the instance's config or lifecycle, and a layout save is neither (OR-33 guard 1 already answers
-- "unchanged" for it). The record is replaced wholesale by the latest save that carried a layout.
--
-- Numbering (integrator, 2026-10-06): V013 is the next contiguous number after S6's V011 and S7's V012 at merge time.
-- From here on every unmerged sm_data migration (plan 010 T9.3 and T11.2, plan 011 P11-2.2 and later, plan 008) takes
-- the next free number when it merges, so versions always reach a database in ascending order (Flyway outOfOrder off).

CREATE TABLE sm_data.designer_layout (
    tenant_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    -- { "<instanceKey>": { "x": <number>, "y": <number> }, ... }; numbers are stored as the client sent them (a canvas
    -- position may be fractional). Named CHECKs: a refusal is asserted by name.
    layout jsonb NOT NULL
        CONSTRAINT designer_layout_is_object CHECK (jsonb_typeof(layout) = 'object'),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, instance_id),
    CONSTRAINT designer_layout_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id),
    -- Every entry is exactly { x: number, y: number }, checked by three paths, none of which is a negated comparison
    -- (an unknown CHECK passes, and a comparison over a missing member is unknown):
    --   1. STRICT: no entry is a non-object. Strict mode matters: lax auto-unwraps an array entry into its elements, so
    --      an empty array would match nothing and slip through.
    --   2. STRICT over every member of every entry (keyvalue): its key is x or y, and its value is a number (a numeric
    --      value held in an array is not a number, which lax mode would also have unwrapped).
    --   3. LAX existence, which is two-valued: both x and y are present.
    -- coalesce guards the silent-error case the same way V010's predicates do.
    CONSTRAINT designer_layout_positions CHECK (
        NOT coalesce(jsonb_path_exists(layout, 'strict $.* ? (@.type() != "object")', '{}'::jsonb, true), false)
        AND NOT coalesce(jsonb_path_exists(layout,
            'strict $.*.keyvalue() ? ((@.key != "x" && @.key != "y") || @.value.type() != "number")',
            '{}'::jsonb, true), false)
        AND NOT coalesce(jsonb_path_exists(layout, '$.* ? (!exists(@.x) || !exists(@.y))', '{}'::jsonb, true), false)));

-- Grants (explicit). The record is replaced in place and never removed by the application: no DELETE, no TRUNCATE.
-- UPDATE is column-scoped to the three presentation columns, so identity (tenant, instance) is not re-targetable.
GRANT SELECT, INSERT ON sm_data.designer_layout TO sm_dp_runtime;
GRANT UPDATE (layout, updated_by, updated_at) ON sm_data.designer_layout TO sm_dp_runtime;
