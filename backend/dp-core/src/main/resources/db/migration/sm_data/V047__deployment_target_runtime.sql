-- Runtime-specific deployment target metadata is deliberately separate from SSH connection data.
CREATE TABLE sm_data.deployment_target_runtime (
    tenant_id uuid NOT NULL,
    target_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    runtime text NOT NULL CONSTRAINT deployment_target_runtime_kind CHECK (runtime IN ('docker', 'systemd')),
    settings jsonb NOT NULL CONSTRAINT deployment_target_runtime_settings_object
        CHECK (jsonb_typeof(settings) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz NULL,
    CONSTRAINT deployment_target_runtime_pk PRIMARY KEY (tenant_id, target_id)
);
GRANT SELECT, INSERT ON sm_data.deployment_target_runtime TO sm_dp_runtime;
GRANT UPDATE (retired_at) ON sm_data.deployment_target_runtime TO sm_dp_runtime;
