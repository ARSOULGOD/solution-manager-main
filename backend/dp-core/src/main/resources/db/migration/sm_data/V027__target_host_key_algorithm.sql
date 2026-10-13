-- OR-125 (2) / W4C-1 (sm_data V027; plan 013 T1.5 Results, review W4C-1). Run by Flyway as sm_dp_migrator after
-- `SET ROLE sm_dp_owner` (initSql), so everything below is born owned by sm_dp_owner. Keyed by tenant_id first (I5); no
-- foreign key leaves sm_data (I7); a key TYPE is public (it is printed beside the fingerprint by `ssh-keygen -l`), so there is
-- nowhere to put a secret (I6).
--
-- WHY. A target's host key is PINNED by its SHA-256 fingerprint, which does not say what TYPE of key it is a fingerprint of,
-- and sshj lets the client's preference order decide which host-key type a connection negotiates. A pin to any key but the
-- one type sshj prefers never verified, so the interim fix (W4C-1, wave 4) discovered the type by connecting once per key
-- family (up to three attempts, remembered in memory only). The owner ruled the type persisted beside the fingerprint and every
-- SSH connector negotiating it directly (OR-125 (2)).
--
-- THE COLUMN. host_key_algorithm text NULL: the TYPE of the pinned key as `ssh-keygen -l` spells it, one of the five types the
-- data plane's scan can offer (Ed25519, ECDSA over the three NIST curves, RSA). A CHECK holds exactly that set, so a typo or an
-- unknown type is refused at the database instead of becoming a pin no connector can honour. Written at registration and at a
-- re-pin, from the data plane's own fresh scan (OfferedHostKey.algorithm), in the SAME statement as the pin it describes; never
-- client-supplied.
--
-- THE BACK-FILL RULE (scan-free): none can be derived. A SHA-256 fingerprint is a hash of the key blob and cannot be inverted to
-- the blob's type, and no other column holds the type, so no statement here can fill an existing row. The column is therefore
-- NULLABLE and NULL means "type not known": a connector falls back to the interim family discovery ONLY while the value is NULL,
-- and records the exact type of the key that matched the pin on its FIRST SUCCESS (a conditional UPDATE ... WHERE
-- host_key_algorithm IS NULL, so a recorded type is never overwritten by a guess), after which every connection is a single
-- attempt. A replay of the registration back-fills it too, from its fresh scan. Every row that exists when this migration runs
-- stays NULL; nothing is rewritten.
ALTER TABLE sm_data.target_connection ADD COLUMN host_key_algorithm text NULL
    CONSTRAINT target_connection_host_key_algorithm CHECK (host_key_algorithm IN
        ('ssh-ed25519', 'ecdsa-sha2-nistp256', 'ecdsa-sha2-nistp384', 'ecdsa-sha2-nistp521', 'ssh-rsa'));

-- The runtime's UPDATE column grant ONLY: V007 already grants table-level SELECT and INSERT on target_connection, so the new
-- column is readable and insertable already; a re-pin writes it with the pin and a connector records it on first success.
GRANT UPDATE (host_key_algorithm) ON sm_data.target_connection TO sm_dp_runtime;

-- ROLLBACK (not run by Flyway; the migration is forward-only once published): REVOKE UPDATE (host_key_algorithm) ON
-- sm_data.target_connection FROM sm_dp_runtime; ALTER TABLE sm_data.target_connection DROP COLUMN host_key_algorithm (the CHECK
-- goes with it). Connectors fall back to discovery, as before this migration.
