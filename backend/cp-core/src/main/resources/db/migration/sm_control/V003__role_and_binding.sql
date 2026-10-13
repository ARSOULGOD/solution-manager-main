CREATE TABLE sm_control.permission_catalog (
    permission_id text PRIMARY KEY
);

INSERT INTO sm_control.permission_catalog (permission_id) VALUES
    ('instance.bindTarget'), ('target.use'), ('solution.view'), ('package.load'), ('instance.view'),
    ('instance.create'), ('config.view'), ('config.edit'), ('config.apply'), ('config.rollback'),
    ('config.overrideWarning'), ('instance.restart'), ('health.view'), ('logs.read'), ('logs.setLevel'),
    ('metrics.query'), ('metrics.export'), ('metrics.forceRaw'), ('metric.declare'), ('dashboard.view'),
    ('dashboard.edit'), ('dashboard.reset'), ('target.view'), ('target.create'), ('role.view'), ('role.edit'),
    ('role.assign'), ('user.view'), ('user.create'), ('user.deactivate'), ('mesh.ticket.mint'),
    ('mesh.stream.grant'), ('mesh.stream.revoke'), ('authz.introspect.self'), ('authz.introspect.any'),
    ('instance.stop'), ('audit.view'), ('user.edit');

CREATE TABLE sm_control.role (
    role_id uuid PRIMARY KEY,
    name text NOT NULL UNIQUE,
    built_in boolean NOT NULL
);

CREATE TABLE sm_control.role_permission (
    role_id uuid NOT NULL,
    permission_id text NOT NULL,
    CONSTRAINT role_permission_pk PRIMARY KEY (role_id, permission_id),
    CONSTRAINT role_permission_role_fk FOREIGN KEY (role_id)
        REFERENCES sm_control.role (role_id) ON DELETE RESTRICT,
    CONSTRAINT role_permission_catalog_fk FOREIGN KEY (permission_id)
        REFERENCES sm_control.permission_catalog (permission_id) ON DELETE RESTRICT
);

CREATE TABLE sm_control.role_binding (
    binding_id uuid PRIMARY KEY,
    principal_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    CONSTRAINT role_binding_unique UNIQUE (principal_id, role_id, scope_id),
    CONSTRAINT role_binding_principal_fk FOREIGN KEY (principal_id)
        REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    CONSTRAINT role_binding_role_fk FOREIGN KEY (role_id)
        REFERENCES sm_control.role (role_id) ON DELETE RESTRICT,
    CONSTRAINT role_binding_scope_fk FOREIGN KEY (scope_id)
        REFERENCES sm_control.scope (scope_id) ON DELETE RESTRICT
);

CREATE INDEX role_binding_scope_idx ON sm_control.role_binding (scope_id);

INSERT INTO sm_control.role (role_id, name, built_in) VALUES
    ('00000000-0000-4000-8000-000000000001', 'Administrator', true),
    ('00000000-0000-4000-8000-000000000002', 'Viewer', true),
    ('00000000-0000-4000-8000-000000000003', 'Operator', true);

INSERT INTO sm_control.role_permission (role_id, permission_id)
SELECT '00000000-0000-4000-8000-000000000001', permission_id
FROM sm_control.permission_catalog;

INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000002', 'solution.view'),
    ('00000000-0000-4000-8000-000000000002', 'instance.view'),
    ('00000000-0000-4000-8000-000000000002', 'config.view'),
    ('00000000-0000-4000-8000-000000000002', 'health.view'),
    ('00000000-0000-4000-8000-000000000002', 'logs.read'),
    ('00000000-0000-4000-8000-000000000002', 'metrics.query'),
    ('00000000-0000-4000-8000-000000000002', 'dashboard.view'),
    ('00000000-0000-4000-8000-000000000002', 'target.view'),
    ('00000000-0000-4000-8000-000000000003', 'solution.view'),
    ('00000000-0000-4000-8000-000000000003', 'instance.view'),
    ('00000000-0000-4000-8000-000000000003', 'config.view'),
    ('00000000-0000-4000-8000-000000000003', 'health.view'),
    ('00000000-0000-4000-8000-000000000003', 'logs.read'),
    ('00000000-0000-4000-8000-000000000003', 'metrics.query'),
    ('00000000-0000-4000-8000-000000000003', 'dashboard.view'),
    ('00000000-0000-4000-8000-000000000003', 'target.view'),
    ('00000000-0000-4000-8000-000000000003', 'instance.bindTarget'),
    ('00000000-0000-4000-8000-000000000003', 'target.use'),
    ('00000000-0000-4000-8000-000000000003', 'instance.create'),
    ('00000000-0000-4000-8000-000000000003', 'config.edit'),
    ('00000000-0000-4000-8000-000000000003', 'config.apply'),
    ('00000000-0000-4000-8000-000000000003', 'config.rollback'),
    ('00000000-0000-4000-8000-000000000003', 'instance.restart'),
    ('00000000-0000-4000-8000-000000000003', 'metric.declare'),
    ('00000000-0000-4000-8000-000000000003', 'dashboard.edit'),
    ('00000000-0000-4000-8000-000000000003', 'dashboard.reset'),
    ('00000000-0000-4000-8000-000000000003', 'mesh.ticket.mint');
