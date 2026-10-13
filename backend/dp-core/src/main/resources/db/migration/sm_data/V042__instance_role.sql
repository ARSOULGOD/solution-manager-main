ALTER TABLE sm_data.instance_state
    ADD COLUMN role text NULL
        CONSTRAINT instance_role_non_blank CHECK (role IS NULL OR length(btrim(role)) BETWEEN 1 AND 128);

GRANT INSERT (role) ON sm_data.instance_state TO sm_dp_runtime;
