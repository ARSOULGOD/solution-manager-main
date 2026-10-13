-- Run once by the PostgreSQL 14 bootstrap superuser before the Flyway migration job.
-- Login credentials are installer-managed and deliberately absent from this artifact. The Phase 1
-- Flyway job must receive only SM_BOOTSTRAP_CREDENTIAL_HASH as bootstrap_credential_hash; it is an
-- Argon2id hash generated from the installer-supplied single-use secret, never the plaintext secret.
CREATE ROLE sm_cp_owner NOLOGIN;
CREATE ROLE sm_cp_migrator LOGIN IN ROLE sm_cp_owner;
CREATE ROLE sm_cp_runtime LOGIN NOINHERIT;

GRANT sm_cp_owner TO CURRENT_USER;
CREATE SCHEMA sm_control AUTHORIZATION sm_cp_owner;
GRANT USAGE ON SCHEMA sm_control TO sm_cp_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
