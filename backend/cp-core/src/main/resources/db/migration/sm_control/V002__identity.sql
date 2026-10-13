CREATE TABLE sm_control.principal (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    source text NOT NULL,
    username text NOT NULL,
    credential_hash text,
    status text NOT NULL DEFAULT 'active',
    must_rotate boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    deactivated_at timestamptz,
    CONSTRAINT principal_source CHECK (source IN ('local', 'idp')),
    CONSTRAINT principal_local_needs_hash CHECK (source <> 'local' OR credential_hash IS NOT NULL),
    CONSTRAINT principal_status CHECK (status IN ('active', 'deactivated')),
    CONSTRAINT principal_deactivation_state CHECK (
        (status = 'active' AND deactivated_at IS NULL)
        OR (status = 'deactivated' AND deactivated_at IS NOT NULL)),
    CONSTRAINT principal_organization_fk FOREIGN KEY (organization_id)
        REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    CONSTRAINT principal_username_unique UNIQUE (username)
);
