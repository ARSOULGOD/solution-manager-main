CREATE TABLE sm_control.session (
    session_id uuid PRIMARY KEY,
    principal_id uuid NOT NULL,
    token_hash text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz NOT NULL,
    idle_expires_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT session_principal_fk FOREIGN KEY (principal_id)
        REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    CONSTRAINT session_token_hash_shape CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT session_idle_within_absolute CHECK (idle_expires_at <= absolute_expires_at)
);

CREATE INDEX session_principal_idx ON sm_control.session (principal_id);
