-- PLAN 011 P11-5.1 (sm_data; the plan reserved V016 for this, the lane built it as the placeholder V018, and the integrator
-- renumbered it to V017, the next free number at merge, exactly as P11-2.2's V015 became V016). Run by Flyway as sm_dp_migrator
-- after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by sm_dp_owner. Keyed by tenant_id first (I5).
--
-- OR-107 OQ-3 (a): ONE broker connection per (organization, environment), registered by a DP operator command
-- (BrokerConnectionMain, the TargetConnectionMain shape). The Data Plane subscribes DIRECTLY on the customer broker the MI
-- publishes to, with no relay through SM's own mesh. The credential is NOT in this table: credential_ref is a reference into
-- sm_secret.secret, under the NEW purpose BROKER_CREDENTIAL (I6).

-- ==== 1. BROKER_CREDENTIAL: BE-D43 amends BE-D28's closed purpose set ==============================================
-- The purpose is part of the authenticated data (BE-D28), so a broker credential labelled CONFIG_FIELD would be a broker
-- credential that opens as a config field. The CHECK and SecretPurpose.java name the same four stored purposes (CANARY never
-- lives in this table).
ALTER TABLE sm_secret.secret DROP CONSTRAINT secret_purpose;
ALTER TABLE sm_secret.secret ADD CONSTRAINT secret_purpose
    CHECK (purpose IN ('SSH_PRIVATE_KEY', 'CONFIG_FIELD', 'CONFIG_HASH_KEY', 'BROKER_CREDENTIAL'));

-- ==== 2. the connection =============================================================================================
-- The field grammars are SM's own, closed, and deliberately free of anything a shell, a URL or a topic treats as syntax: the
-- values reach the JCSMP property map, never a command line. The broker is the authority on what it accepts (I2: no Solace
-- limit is asserted here).
CREATE TABLE sm_data.broker_connection (
    tenant_id uuid NOT NULL,
    environment_id uuid NOT NULL,                       -- an opaque control-plane id: no foreign key leaves sm_data (I7)
    broker_connection_id uuid NOT NULL,
    scheme text NOT NULL CONSTRAINT broker_connection_scheme CHECK (scheme IN ('tcp', 'tcps', 'ws', 'wss')),
    host text NOT NULL
        CONSTRAINT broker_connection_host CHECK (host ~ '^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$'),
    port integer NOT NULL CONSTRAINT broker_connection_port CHECK (port BETWEEN 1 AND 65535),
    vpn text NOT NULL CONSTRAINT broker_connection_vpn CHECK (vpn ~ '^[A-Za-z0-9_.-]{1,64}$'),
    client_username text NOT NULL CONSTRAINT broker_connection_username CHECK (client_username ~ '^[A-Za-z0-9_.@:-]{1,255}$'),
    credential_ref uuid NOT NULL,                       -- sm_secret.secret(secret_id), purpose BROKER_CREDENTIAL; never the credential (I6)
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    retired_at timestamptz NULL,
    PRIMARY KEY (tenant_id, broker_connection_id));

-- ONE LIVE connection per (organization, environment): every instance of the environment that declares a push source uses it.
-- The index leads with tenant_id, so two organizations may each hold one for an environment id (an id means nothing across
-- organizations, I5).
CREATE UNIQUE INDEX broker_connection_live_once ON sm_data.broker_connection (tenant_id, environment_id)
    WHERE retired_at IS NULL;

-- The runtime role reads and registers; it never edits or deletes. Rotating the credential or moving the broker is a new
-- connection (a later plan), not an UPDATE of this one.
GRANT SELECT, INSERT ON sm_data.broker_connection TO sm_dp_runtime;
