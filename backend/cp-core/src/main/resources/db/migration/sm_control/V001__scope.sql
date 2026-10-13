-- No CREATE SCHEMA here, deliberately — mirroring dp-core's V001, which carries none either.
-- Phase 0 (db/bootstrap/sm_control_phase0.sql) already ran `CREATE SCHEMA sm_control AUTHORIZATION
-- sm_cp_owner`. Re-issuing it here, even as IF NOT EXISTS, was measured to fail against a
-- phase0-only database with `permission denied for database <name>` (SmControlMigrationMainTest):
-- Postgres checks database-level CREATE privilege before it ever gets to decide the schema already
-- exists and skip, and phase0.sql grants no such privilege — nor should it, since Phase 1 has no
-- reason to create a schema Phase 0 is already responsible for.
CREATE TABLE sm_control.organization (
    organization_id uuid PRIMARY KEY
);

CREATE TABLE sm_control.organization_unit (
    organization_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    parent_unit_id uuid NULL,
    CONSTRAINT organization_unit_pk PRIMARY KEY (organization_id, organization_unit_id),
    CONSTRAINT organization_unit_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    CONSTRAINT organization_unit_parent_fk
        FOREIGN KEY (organization_id, parent_unit_id)
        REFERENCES sm_control.organization_unit (organization_id, organization_unit_id) ON DELETE RESTRICT
);

CREATE TABLE sm_control.environment (
    organization_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    CONSTRAINT environment_pk PRIMARY KEY (organization_id, organization_unit_id, environment_id),
    CONSTRAINT environment_organization_unit_fk
        FOREIGN KEY (organization_id, organization_unit_id)
        REFERENCES sm_control.organization_unit (organization_id, organization_unit_id) ON DELETE RESTRICT
);

CREATE TABLE sm_control.solution (
    organization_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    solution_id uuid NOT NULL,
    CONSTRAINT solution_pk PRIMARY KEY (organization_id, organization_unit_id, environment_id, solution_id),
    CONSTRAINT solution_environment_fk
        FOREIGN KEY (organization_id, organization_unit_id, environment_id)
        REFERENCES sm_control.environment (organization_id, organization_unit_id, environment_id) ON DELETE RESTRICT
);

CREATE TABLE sm_control.instance (
    organization_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    solution_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    CONSTRAINT instance_pk PRIMARY KEY (
        organization_id, organization_unit_id, environment_id, solution_id, instance_id),
    CONSTRAINT instance_solution_fk
        FOREIGN KEY (organization_id, organization_unit_id, environment_id, solution_id)
        REFERENCES sm_control.solution (
            organization_id, organization_unit_id, environment_id, solution_id) ON DELETE RESTRICT
);

CREATE TABLE sm_control.scope (
    scope_id uuid PRIMARY KEY,
    kind text NOT NULL CONSTRAINT scope_kind CHECK (
        kind IN ('install', 'organization', 'organization_unit', 'environment', 'solution', 'instance')),
    organization_id uuid NULL,
    organization_unit_id uuid NULL,
    environment_id uuid NULL,
    solution_id uuid NULL,
    instance_id uuid NULL,
    CONSTRAINT scope_shape CHECK (
         (kind = 'install'           AND organization_id IS NULL     AND organization_unit_id IS NULL
                                     AND environment_id IS NULL      AND solution_id IS NULL
                                     AND instance_id IS NULL)
      OR (kind = 'organization'      AND organization_id IS NOT NULL AND organization_unit_id IS NULL
                                     AND environment_id IS NULL      AND solution_id IS NULL
                                     AND instance_id IS NULL)
      OR (kind = 'organization_unit' AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id IS NULL      AND solution_id IS NULL
                                     AND instance_id IS NULL)
      OR (kind = 'environment'       AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id IS NOT NULL  AND solution_id IS NULL
                                     AND instance_id IS NULL)
      OR (kind = 'solution'          AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id IS NOT NULL  AND solution_id IS NOT NULL
                                     AND instance_id IS NULL)
      OR (kind = 'instance'          AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id IS NOT NULL  AND solution_id IS NOT NULL
                                     AND instance_id IS NOT NULL)),
    CONSTRAINT scope_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    CONSTRAINT scope_organization_unit_fk
        FOREIGN KEY (organization_id, organization_unit_id)
        REFERENCES sm_control.organization_unit (organization_id, organization_unit_id) ON DELETE RESTRICT,
    CONSTRAINT scope_environment_fk
        FOREIGN KEY (organization_id, organization_unit_id, environment_id)
        REFERENCES sm_control.environment (organization_id, organization_unit_id, environment_id) ON DELETE RESTRICT,
    CONSTRAINT scope_solution_fk
        FOREIGN KEY (organization_id, organization_unit_id, environment_id, solution_id)
        REFERENCES sm_control.solution (organization_id, organization_unit_id, environment_id, solution_id)
        ON DELETE RESTRICT,
    CONSTRAINT scope_instance_fk
        FOREIGN KEY (organization_id, organization_unit_id, environment_id, solution_id, instance_id)
        REFERENCES sm_control.instance (
            organization_id, organization_unit_id, environment_id, solution_id, instance_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX scope_one_install ON sm_control.scope (kind) WHERE kind = 'install';
CREATE UNIQUE INDEX scope_one_organization ON sm_control.scope (organization_id) WHERE kind = 'organization';
CREATE UNIQUE INDEX scope_one_organization_unit ON sm_control.scope (organization_id, organization_unit_id)
    WHERE kind = 'organization_unit';
CREATE UNIQUE INDEX scope_one_environment ON sm_control.scope (
    organization_id, organization_unit_id, environment_id) WHERE kind = 'environment';
CREATE UNIQUE INDEX scope_one_solution ON sm_control.scope (
    organization_id, organization_unit_id, environment_id, solution_id) WHERE kind = 'solution';
CREATE UNIQUE INDEX scope_one_instance ON sm_control.scope (
    organization_id, organization_unit_id, environment_id, solution_id, instance_id) WHERE kind = 'instance';
