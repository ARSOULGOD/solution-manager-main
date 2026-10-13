-- Carry the control-plane Connection's optional private-key passphrase reference into the DP projection.
-- Only the opaque reference is stored here; the secret remains in the tenant-scoped secret store.
ALTER TABLE sm_data.connection_runtime ADD COLUMN IF NOT EXISTS passphrase_ref uuid;
