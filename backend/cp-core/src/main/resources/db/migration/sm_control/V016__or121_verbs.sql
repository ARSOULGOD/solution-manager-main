-- Plan 012 T1 (OR-121, confirmed by name in OR-122): the permission catalog grows from 38 to 43, and the Operator
-- gains three verbs, so the built-in roles become Administrator 43 / Operator 24 / Viewer 8.
-- Authority: docs/design/D10-G2-APPROVAL-2026-09-15.md section 1.9 (rows 39-43) and section 1.9.1 (the same rows in the
-- counted form, appended in the commit that adds this file). PermissionCatalogMirrorTest reads that record.
-- Never edit this file once applied (OR-22): a correction is a new migration, never an edit here.
--
-- This migration touches no function and no table definition: it is data only, in the two tables V003 created. The
-- migrator's inserts are audited with NULL context by the V011 triggers (V012's rule for non-runtime roles), exactly as
-- V014's two Operator verbs were. Nothing here changes a privilege: sm_cp_runtime holds SELECT on role_permission and no
-- write on either table (V006), so the application can neither add a verb nor grant one.
--
-- The foreign key role_permission_catalog_fk is why the catalog rows come first: a grant of a verb the catalog does
-- not hold is refused by the database (23503), so an unapproved verb cannot be granted by any path.

INSERT INTO sm_control.permission_catalog (permission_id) VALUES
    ('instance.undeclare'),               -- row 39, HIGH: removes an instance from SM; never touches the workload (BE-D49)
    ('instance.restart.overrideSafety'),  -- row 40, HIGH: needed beside config.apply + instance.restart for the two ungated strategies (BE-D51)
    ('target.edit'),                      -- row 41, HIGH: read / edit / re-pin / rotate / retire a target connection (BE-D54)
    ('hierarchy.edit'),                   -- row 42, STANDARD: create and rename sub-organizations and environments (BE-D57)
    ('entitlement.set');                  -- row 43, HIGH, install scope: set an organization's entitlement (amends OR-48)

-- The Administrator (V003's id ...0001) holds every catalog verb: the five new ones, by name.
INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000001', 'instance.undeclare'),
    ('00000000-0000-4000-8000-000000000001', 'instance.restart.overrideSafety'),
    ('00000000-0000-4000-8000-000000000001', 'target.edit'),
    ('00000000-0000-4000-8000-000000000001', 'hierarchy.edit'),
    ('00000000-0000-4000-8000-000000000001', 'entitlement.set');

-- The Operator (...0003) gains exactly three: instance.undeclare (OPS-Q3 P-1), instance.restart.overrideSafety
-- (OPS-Q6 B-1) and instance.stop (OPS-Q11 (a), which amends D10's role table: 21 becomes 24). target.edit,
-- hierarchy.edit and entitlement.set stay Administrator-only (D-REG OQ-7 (a)).
INSERT INTO sm_control.role_permission (role_id, permission_id) VALUES
    ('00000000-0000-4000-8000-000000000003', 'instance.undeclare'),
    ('00000000-0000-4000-8000-000000000003', 'instance.restart.overrideSafety'),
    ('00000000-0000-4000-8000-000000000003', 'instance.stop');
