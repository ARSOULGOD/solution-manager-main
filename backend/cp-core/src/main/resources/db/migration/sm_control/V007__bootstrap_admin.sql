-- The installer supplies an Argon2id hash for a single-use bootstrap secret as a Flyway placeholder.
-- Plaintext credentials never enter this migration or the database.
INSERT INTO sm_control.organization (organization_id)
VALUES ('00000000-0000-4000-8000-000000000011');

INSERT INTO sm_control.scope (scope_id, kind)
VALUES ('00000000-0000-4000-8000-000000000010', 'install');

INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash, must_rotate)
VALUES ('00000000-0000-4000-8000-000000000012',
        '00000000-0000-4000-8000-000000000011',
        'local',
        'bootstrap-admin',
        '${bootstrap_credential_hash}',
        true);

INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)
VALUES ('00000000-0000-4000-8000-000000000013',
        '00000000-0000-4000-8000-000000000012',
        '00000000-0000-4000-8000-000000000001',
        '00000000-0000-4000-8000-000000000010');
