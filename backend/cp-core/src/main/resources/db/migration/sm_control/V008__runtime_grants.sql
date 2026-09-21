GRANT UPDATE (credential_hash, must_rotate) ON TABLE sm_control.principal TO sm_cp_runtime;

GRANT SELECT ON TABLE sm_control.scope, sm_control.organization, sm_control.organization_unit,
    sm_control.environment, sm_control.solution, sm_control.instance TO sm_cp_runtime;
