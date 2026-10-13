-- Plan 013: connection objects keep their secret-typed attributes in the envelope
-- store under their own purpose. V017 widened this check for broker credentials;
-- this migration widens it again for Connections.
ALTER TABLE sm_secret.secret DROP CONSTRAINT secret_purpose;
ALTER TABLE sm_secret.secret ADD CONSTRAINT secret_purpose
    CHECK (purpose IN ('SSH_PRIVATE_KEY', 'CONFIG_FIELD', 'CONFIG_HASH_KEY',
                       'BROKER_CREDENTIAL', 'CONNECTION_CREDENTIAL'));
