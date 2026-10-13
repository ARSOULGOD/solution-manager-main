-- PLAN 010 T2.4 — the DP secret store (docs/design/backend/secret-store.md, "D-KEY"; OR-30, OR-85).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by
-- sm_dp_owner. The schema sm_secret itself is created by Phase 0 (sm_data_phase0.sql), because a migrator cannot
-- create a schema. These tables live in sm_secret, not sm_data, and are schema-qualified for that reason.
--
-- GRANTS ARE EXPLICIT AND PER TABLE (D-KEY 8). sm_data's V001 default read-baseline does not reach sm_secret. The
-- runtime role gets:
--   secret      SELECT; INSERT of the listed columns; UPDATE of (key_id, wrapped_dek, dek_iv) ONLY — rotation changes
--               exactly those three, in one statement. The value columns (value_iv, ciphertext), the identity
--               (tenant_id, secret_id, purpose) and aad_version are NOT writable by the runtime role, so "the
--               ciphertext of a value never changes" is a privilege, not a promise.
--   kek_canary  SELECT, INSERT (the boot mints; nothing updates or deletes a canary).
--   neither     DELETE. Purging a superseded row is the owner's act (OR-85, OQ-4): the runtime never needs it.
--
-- LENGTHS ARE FIXED AND CHECKED (D-KEY 3.2): IVs 12 bytes; wrapped_dek 48 (a 32-byte DEK plus the 16-byte GCM tag,
-- measured); ciphertext 16 to 16400 bytes. The cap is a value, not a design question: T2.4 picks 16 KiB of plaintext
-- (a private key is a few kilobytes; an RSA-4096 PEM is about 3.3 KB) plus the 16-byte tag. It mirrors
-- EnvelopeSecretStore.MAX_VALUE_BYTES.

CREATE TABLE sm_secret.secret (
    tenant_id uuid NOT NULL,
    secret_id uuid NOT NULL,
    purpose text NOT NULL
        CONSTRAINT secret_purpose CHECK (purpose IN ('SSH_PRIVATE_KEY', 'CONFIG_FIELD', 'CONFIG_HASH_KEY')),
    key_id text NOT NULL
        CONSTRAINT secret_key_id CHECK (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    wrapped_dek bytea NOT NULL CONSTRAINT secret_wrapped_dek_length CHECK (octet_length(wrapped_dek) = 48),
    dek_iv bytea NOT NULL CONSTRAINT secret_dek_iv_length CHECK (octet_length(dek_iv) = 12),
    value_iv bytea NOT NULL CONSTRAINT secret_value_iv_length CHECK (octet_length(value_iv) = 12),
    ciphertext bytea NOT NULL CONSTRAINT secret_ciphertext_length CHECK (octet_length(ciphertext) BETWEEN 16 AND 16400),
    aad_version smallint NOT NULL CONSTRAINT secret_aad_version CHECK (aad_version >= 1),
    -- Non-null exactly for a CONFIG_HASH_KEY (D-KEY 7.2): the per-tenant, never-reused version of the keyed hash.
    key_version integer NULL CONSTRAINT secret_key_version_positive CHECK (key_version >= 1),
    created_at timestamptz NOT NULL,
    -- Null only for a hash key, which is minted lazily by whichever caller needs it first, as a system action.
    created_by uuid NULL,
    PRIMARY KEY (tenant_id, secret_id),
    CONSTRAINT secret_key_version_iff_hash_key CHECK ((purpose = 'CONFIG_HASH_KEY') = (key_version IS NOT NULL)),
    CONSTRAINT secret_created_by_unless_hash_key CHECK (created_by IS NOT NULL OR purpose = 'CONFIG_HASH_KEY'),
    CONSTRAINT secret_hash_key_version_once UNIQUE (tenant_id, key_version));

-- Defence in depth for the write-once-value property (D-KEY 8): the column privilege already stops the runtime
-- role; this also binds the owner. The three rotation columns are the only ones an UPDATE may change.
CREATE FUNCTION sm_secret.secret_value_is_write_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.secret_id IS DISTINCT FROM OLD.secret_id
       OR NEW.purpose IS DISTINCT FROM OLD.purpose
       OR NEW.value_iv IS DISTINCT FROM OLD.value_iv
       OR NEW.ciphertext IS DISTINCT FROM OLD.ciphertext
       OR NEW.aad_version IS DISTINCT FROM OLD.aad_version
       OR NEW.key_version IS DISTINCT FROM OLD.key_version
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'a secret value is write-once: only key_id, wrapped_dek and dek_iv may change'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER secret_write_once BEFORE UPDATE ON sm_secret.secret
    FOR EACH ROW EXECUTE FUNCTION sm_secret.secret_value_is_write_once();

GRANT SELECT ON sm_secret.secret TO sm_dp_runtime;
GRANT INSERT (tenant_id, secret_id, purpose, key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version,
              key_version, created_at, created_by) ON sm_secret.secret TO sm_dp_runtime;
GRANT UPDATE (key_id, wrapped_dek, dek_iv) ON sm_secret.secret TO sm_dp_runtime;

-- The boot canary (D-KEY 4.3): an ordinary envelope per key id, INSTALL-LEVEL — no tenant_id, so the boot check
-- never reads tenant data (B4). Never re-wrapped: a rotation mints a new one for the new id and leaves the old.
CREATE TABLE sm_secret.kek_canary (
    key_id text PRIMARY KEY
        CONSTRAINT kek_canary_key_id CHECK (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    wrapped_dek bytea NOT NULL CONSTRAINT kek_canary_wrapped_dek_length CHECK (octet_length(wrapped_dek) = 48),
    dek_iv bytea NOT NULL CONSTRAINT kek_canary_dek_iv_length CHECK (octet_length(dek_iv) = 12),
    value_iv bytea NOT NULL CONSTRAINT kek_canary_value_iv_length CHECK (octet_length(value_iv) = 12),
    ciphertext bytea NOT NULL CONSTRAINT kek_canary_ciphertext_length CHECK (octet_length(ciphertext) BETWEEN 16 AND 16400),
    aad_version smallint NOT NULL CONSTRAINT kek_canary_aad_version CHECK (aad_version >= 1),
    created_at timestamptz NOT NULL);

GRANT SELECT, INSERT ON sm_secret.kek_canary TO sm_dp_runtime;
