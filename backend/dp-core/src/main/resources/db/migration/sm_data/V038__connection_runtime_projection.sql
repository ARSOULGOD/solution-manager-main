-- Plan 014: runtime-safe projection of a control-plane remote-server Connection.
-- The Data Plane never reads sm_control.connection directly. The gateway/connection
-- registration seam owns this projection and refreshes it when a Connection changes.
-- Existing target_connection and target-backed placements are intentionally untouched.
CREATE TABLE sm_data.connection_runtime (
    tenant_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    connection_type text NOT NULL CONSTRAINT connection_runtime_type CHECK (connection_type = 'remote-server'),
    host text NOT NULL
        CONSTRAINT connection_runtime_host CHECK (host ~ '^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$'),
    port integer NOT NULL CONSTRAINT connection_runtime_port CHECK (port BETWEEN 1 AND 65535),
    ssh_user text NOT NULL CONSTRAINT connection_runtime_user CHECK (ssh_user ~ '^[A-Za-z_][A-Za-z0-9_-]{0,31}$'),
    host_key_fingerprint text NOT NULL
        CONSTRAINT connection_runtime_fp CHECK (host_key_fingerprint ~ '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$'),
    credential_ref uuid NOT NULL,
    allowed_config_root text NOT NULL
        CONSTRAINT connection_runtime_config_root CHECK (
            allowed_config_root ~ '^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$' AND strpos(allowed_config_root, '..') = 0),
    allowed_container_prefix text NOT NULL
        CONSTRAINT connection_runtime_container_prefix CHECK (
            allowed_container_prefix ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    created_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, connection_id));

CREATE INDEX connection_runtime_live ON sm_data.connection_runtime (tenant_id, connection_id)
    WHERE retired_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON sm_data.connection_runtime TO sm_dp_runtime;
