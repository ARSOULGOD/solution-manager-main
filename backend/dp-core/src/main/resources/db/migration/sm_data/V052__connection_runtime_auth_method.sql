-- Preserve how the parent Connection authenticates. The value is non-secret metadata; the credential remains in the
-- tenant-scoped secret store. New projection writes always provide this value. Existing rows default to private-key
-- until their Connection is re-projected (the pre-existing apply path only supported key authentication).
ALTER TABLE sm_data.connection_runtime ADD COLUMN IF NOT EXISTS auth_method text;
UPDATE sm_data.connection_runtime SET auth_method = 'private-key' WHERE auth_method IS NULL;
ALTER TABLE sm_data.connection_runtime ALTER COLUMN auth_method SET DEFAULT 'private-key';
ALTER TABLE sm_data.connection_runtime ALTER COLUMN auth_method SET NOT NULL;
ALTER TABLE sm_data.connection_runtime ADD CONSTRAINT connection_runtime_auth_method
    CHECK (auth_method IN ('basic', 'private-key'));
