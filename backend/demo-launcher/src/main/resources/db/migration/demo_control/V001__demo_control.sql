CREATE SCHEMA IF NOT EXISTS demo_control;

CREATE TABLE demo_control.account (
    account_id uuid PRIMARY KEY,
    login_name text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    vault_salt bytea NOT NULL,
    wrapped_vault_key bytea NOT NULL,
    wrap_iv bytea NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE demo_control.ssh_key (
    key_id uuid PRIMARY KEY,
    key_reference text NOT NULL UNIQUE,
    encrypted_pem bytea NOT NULL,
    iv bytea NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (key_reference ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$')
);

CREATE TABLE demo_control.solution (
    solution_id uuid PRIMARY KEY,
    package_name text NOT NULL,
    package_version jsonb NOT NULL,
    package_json jsonb NOT NULL,
    package_digest text NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (package_name ~ '^[a-z][a-z0-9_-]{0,63}$'),
    CHECK (package_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE demo_control.instance (
    instance_id uuid PRIMARY KEY,
    solution_id uuid NOT NULL REFERENCES demo_control.solution(solution_id),
    display_name text NOT NULL,
    ssh_host text NOT NULL,
    ssh_port integer NOT NULL,
    ssh_user text NOT NULL,
    ssh_key_reference text NOT NULL REFERENCES demo_control.ssh_key(key_reference),
    host_key_fingerprint text NOT NULL,
    container_name text NOT NULL,
    remote_config_directory text NOT NULL,
    container_config_path text NOT NULL,
    config_file_name text NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (ssh_port BETWEEN 1 AND 65535)
);

CREATE TABLE demo_control.audit_log (
    audit_id uuid PRIMARY KEY,
    action text NOT NULL,
    subject_id uuid,
    created_at timestamptz NOT NULL,
    CHECK (action ~ '^[a-z][a-z0-9_.-]{0,63}$')
);
