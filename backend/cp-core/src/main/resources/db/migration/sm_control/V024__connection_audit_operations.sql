-- Corrective migration for databases that applied the original Connections migration before its
-- change-audit vocabulary was completed. Never edit V021: OR-22 requires a new migration for a
-- correction to an already-published migration.
--
-- The connection API tests connectivity before writing the connection row. On a successful probe,
-- the subsequent write must be audited as one of the four connection operations below. Older local
-- databases still have the pre-Connections audit function, so they reject the write with OR-25.

DO $migration$
DECLARE
    definition text;
    patched text;
    old_allow_list text := $$'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED') THEN$$;
    new_allow_list text := $$'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED',
                'CONNECTION_CREATED', 'CONNECTION_UPDATED', 'CONNECTION_DEACTIVATED', 'CONNECTION_DELETED') THEN$$;
    old_row_key text := $row_key$
        WHEN 'target' THEN
            jsonb_build_object('target_id', COALESCE(v_new_json ->> 'target_id', v_old_json ->> 'target_id'))
        ELSE
$row_key$;
    new_row_key text := $row_key$
        WHEN 'target' THEN
            jsonb_build_object('target_id', COALESCE(v_new_json ->> 'target_id', v_old_json ->> 'target_id'))
        WHEN 'connection' THEN
            jsonb_build_object('connection_id', COALESCE(v_new_json ->> 'connection_id', v_old_json ->> 'connection_id'))
        ELSE
$row_key$;
BEGIN
    SELECT pg_get_functiondef(p.oid)
      INTO definition
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'sm_control'
       AND p.proname = 'record_change_audit'
       AND pg_get_function_identity_arguments(p.oid) = '';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'sm_control.record_change_audit() is missing';
    END IF;

    IF position('CONNECTION_UPDATED' IN definition) = 0 THEN
        patched := replace(definition, old_allow_list, new_allow_list);
        patched := replace(patched, old_row_key, new_row_key);
        IF patched = definition THEN
            RAISE EXCEPTION 'could not patch sm_control.record_change_audit() for connection operations';
        END IF;
        EXECUTE patched;
    END IF;
END
$migration$;

-- Keep the table-level vocabulary aligned with the trigger allow-list. This is idempotent for
-- databases where the original V021 constraint already contains these values.
ALTER TABLE sm_control.change_audit DROP CONSTRAINT IF EXISTS change_audit_audit_operation;
ALTER TABLE sm_control.change_audit ADD CONSTRAINT change_audit_audit_operation CHECK (
    audit_operation IS NULL OR audit_operation IN (
        'LOGIN_SUCCEEDED', 'LOGIN_REFUSED', 'LOGOUT', 'CREDENTIAL_ROTATED',
        'CREDENTIAL_ROTATION_REFUSED', 'LOCKOUT_CLEARED',
        'INSTANCE_DECLARED', 'PACKAGE_LOADED', 'ENTITLEMENT_SET', 'TARGET_REGISTERED', 'TARGET_RETIRED',
        'DASHBOARD_WIDGET_SAVED', 'DASHBOARD_LAYOUT_SET', 'DASHBOARD_WIDGET_HIDDEN', 'DASHBOARD_RESET',
        'INSTANCE_UNDECLARED',
        'HIERARCHY_NODE_CREATED', 'HIERARCHY_NODE_RENAMED',
        'PRINCIPAL_CREATED', 'PRINCIPAL_DEACTIVATED', 'PRINCIPAL_CREDENTIAL_RESET',
        'ROLE_BINDING_GRANTED', 'ROLE_BINDING_REVOKED',
        'CONNECTION_CREATED', 'CONNECTION_UPDATED', 'CONNECTION_DEACTIVATED', 'CONNECTION_DELETED'));
