REVOKE ALL PRIVILEGES ON SCHEMA sm_control FROM sm_cp_runtime;
GRANT USAGE ON SCHEMA sm_control TO sm_cp_runtime;

REVOKE ALL PRIVILEGES ON TABLE sm_control.role, sm_control.role_permission, sm_control.role_binding
    FROM sm_cp_runtime;
GRANT SELECT ON TABLE sm_control.role, sm_control.role_permission, sm_control.role_binding TO sm_cp_runtime;

REVOKE DELETE ON TABLE sm_control.principal FROM sm_cp_runtime;
REVOKE ALL PRIVILEGES ON TABLE sm_control.principal FROM sm_cp_runtime;
GRANT SELECT ON TABLE sm_control.principal TO sm_cp_runtime;

REVOKE ALL PRIVILEGES ON TABLE sm_control.session FROM sm_cp_runtime;
GRANT SELECT, INSERT ON TABLE sm_control.session TO sm_cp_runtime;
GRANT UPDATE (last_used_at, idle_expires_at, revoked_at) ON TABLE sm_control.session TO sm_cp_runtime;

REVOKE ALL PRIVILEGES ON TABLE sm_control.audit_log FROM sm_cp_runtime;
GRANT SELECT, INSERT ON TABLE sm_control.audit_log TO sm_cp_runtime;
REVOKE ALL PRIVILEGES ON SEQUENCE sm_control.audit_log_audit_id_seq FROM sm_cp_runtime;
GRANT USAGE ON SEQUENCE sm_control.audit_log_audit_id_seq TO sm_cp_runtime;
