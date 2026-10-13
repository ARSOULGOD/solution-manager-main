-- PLAN 013 T1.3 (sm_data V022; docs/design/backend/registration-api.md section 9 and section 10.2, BE-D56). Run by
-- Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by sm_dp_owner.
-- Keyed by tenant_id first (I5); no foreign key leaves sm_data (I7); no key, no credential_ref and no host value is
-- stored in the new table (I6): it holds a kind, an actor, field NAMES and a PUBLIC fingerprint, so there is nowhere to
-- put a secret.
--
-- WHAT THIS DOES NOT DO. It adds the column and the table and nothing reads or writes either yet: the registration route
-- (plan 013 T1.4) writes the fingerprint on insert and rotation and back-fills a legacy NULL on a replay; the edit,
-- re-pin, rotate and retire route (T1.5) writes the events. Every existing row, statement and test is unchanged by it.

-- ==== 1. the stored key's PUBLIC fingerprint (section 10.2) =======================================================
-- What replay and rotation compare (section 8): the fingerprint of the public half of the stored key, in the spelling
-- `ssh-keygen -l` prints (SHA256:<base64>), derived by PrivateKeyInspector. NULL for a row registered before this
-- column existed ("unknown" in CredentialSummary); the first replay or rotation of such a row derives it from the stored
-- key in memory and back-fills it. The grammar is host_key_fingerprint's, so one spelling serves both.
ALTER TABLE sm_data.target_connection ADD COLUMN credential_public_fingerprint text NULL
    CONSTRAINT target_connection_credential_fp CHECK (
        credential_public_fingerprint ~ '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$');

-- The runtime's UPDATE column grant ONLY: V007 already grants table-level SELECT and INSERT on target_connection, so the
-- new column is readable and insertable already, and rotation and the back-fill need exactly this one UPDATE.
GRANT UPDATE (credential_public_fingerprint) ON sm_data.target_connection TO sm_dp_runtime;

-- ==== 2. target_connection_event - the data plane's change audit for a connection record (section 9, BE-D56) =====
-- The data plane had no change audit: target_connection carries only created_by, and an edit overwrites. Every insert,
-- edit, re-pin, rotation and retirement of a connection record writes ONE row here, in the SAME transaction as the
-- change (the service's job; the database cannot see the pairing), so the audit and the change commit or vanish together.
-- Append-only: the runtime role gets SELECT and INSERT and no UPDATE or DELETE on any column.
CREATE TABLE sm_data.target_connection_event (
    tenant_id uuid NOT NULL,
    target_id uuid NOT NULL,
    event_id uuid NOT NULL,                              -- minted by the data plane
    kind text NOT NULL
        CONSTRAINT target_connection_event_kind CHECK (kind IN
            ('registered', 'edited', 'repinned', 'credential_rotated', 'retired')),
    actor uuid NOT NULL,                                 -- the principal id of the session that changed it
    correlation_id uuid NOT NULL,                        -- the request's X-Correlation-Id
    client_address text NOT NULL
        CONSTRAINT target_connection_event_client_address CHECK (client_address ~ '^[A-Za-z0-9:._%-]{1,64}$'),
    -- The NAMES of the fields a change touched, never a value (old or new): the host is not a secret, but this table is
    -- not a second copy of the record. A closed grammar per element, in one expression because a CHECK cannot hold a
    -- subquery; a NULL element would vanish from array_to_string, so it is refused on its own.
    changed_fields text[] NOT NULL DEFAULT '{}'
        CONSTRAINT target_connection_event_fields CHECK (
            cardinality(changed_fields) <= 16
            AND array_position(changed_fields, NULL) IS NULL
            AND array_to_string(changed_fields, ',')
                ~ '^([A-Za-z][A-Za-z0-9_]{0,63}(,[A-Za-z][A-Za-z0-9_]{0,63})*)?$'),
    public_key_fingerprint text NULL
        CONSTRAINT target_connection_event_fp CHECK (
            public_key_fingerprint ~ '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$'),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, target_id, event_id),
    -- Within sm_data, so I7 holds: an event names a connection the data plane really has, and the key leads with tenant_id.
    CONSTRAINT target_connection_event_has_target FOREIGN KEY (tenant_id, target_id)
        REFERENCES sm_data.target_connection (tenant_id, target_id),
    -- The public fingerprint is the replay key of the credential: it rides the two events that set a key and no other
    -- (an 'edited' or 'repinned' event must not smuggle a value into a field that names a credential).
    CONSTRAINT target_connection_event_fp_iff_key_set CHECK (
        (kind IN ('registered', 'credential_rotated')) = (public_key_fingerprint IS NOT NULL)),
    -- An edit or a re-pin that names no field changed nothing worth auditing: the service does not write it.
    CONSTRAINT target_connection_event_names_fields CHECK (
        kind NOT IN ('edited', 'repinned') OR cardinality(changed_fields) >= 1));

-- Section 9: SELECT and INSERT, nothing else. No column-scoped INSERT list is needed (no column is trigger-stamped or
-- runtime-forbidden), and there is NO UPDATE and NO DELETE for the runtime role (the grant is the append-only guarantee).
GRANT SELECT, INSERT ON sm_data.target_connection_event TO sm_dp_runtime;
