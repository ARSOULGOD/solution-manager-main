-- Deployment targets are the runtime-specific projection of a reusable Connection.
-- Existing Target rows remain valid: NULL connection_id means legacy target registration.
ALTER TABLE sm_control.target
    DROP CONSTRAINT target_kind,
    ADD CONSTRAINT target_kind CHECK (kind IN ('docker','systemd','kubernetes','vm')),
    DROP CONSTRAINT target_transport,
    ADD CONSTRAINT target_transport CHECK (transport IN ('ssh-docker','ssh')),
    ADD COLUMN connection_id uuid NULL REFERENCES sm_control.connection(connection_id) ON DELETE RESTRICT,
    ADD COLUMN runtime text NOT NULL DEFAULT 'docker'
        CONSTRAINT target_runtime CHECK (runtime IN ('docker', 'systemd')),
    ADD COLUMN runtime_settings jsonb NOT NULL DEFAULT '{}'::jsonb
        CONSTRAINT target_runtime_settings_object CHECK (jsonb_typeof(runtime_settings) = 'object');

CREATE INDEX target_connection_live ON sm_control.target (organization_id, connection_id)
    WHERE retired_at IS NULL AND connection_id IS NOT NULL;

COMMENT ON COLUMN sm_control.target.connection_id IS
    'Reusable remote-server Connection that supplies SSH access; NULL is a legacy target.';
COMMENT ON COLUMN sm_control.target.runtime IS
    'Runtime adapter selected for this target. Runtime-specific placement belongs to the instance.';
COMMENT ON COLUMN sm_control.target.runtime_settings IS
    'Non-secret target settings such as config root and Docker prefix or systemd unit pattern.';
