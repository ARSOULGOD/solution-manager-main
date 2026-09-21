ALTER TABLE demo_control.instance
    ADD COLUMN IF NOT EXISTS package_version jsonb NOT NULL DEFAULT 'null'::jsonb,
    ADD COLUMN IF NOT EXISTS environment text NOT NULL DEFAULT 'DEV',
    ADD COLUMN IF NOT EXISTS organization_name text NOT NULL DEFAULT 'Local demo',
    ADD COLUMN IF NOT EXISTS sub_organization_name text NOT NULL DEFAULT 'Default',
    ADD COLUMN IF NOT EXISTS driver text NOT NULL DEFAULT 'docker',
    ADD COLUMN IF NOT EXISTS locus text NOT NULL DEFAULT 'dp_remote',
    ADD COLUMN IF NOT EXISTS replicas jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS declared_version text;

ALTER TABLE demo_control.instance
    ADD CONSTRAINT demo_instance_environment_check
    CHECK (environment IN ('DEV', 'TEST', 'PROD'));
