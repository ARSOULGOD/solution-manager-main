ALTER TABLE sm_control.principal
    ADD COLUMN credential_failure_count integer NOT NULL DEFAULT 0
    CONSTRAINT principal_credential_failure_count_nonnegative CHECK (credential_failure_count >= 0);

GRANT UPDATE (credential_failure_count) ON TABLE sm_control.principal TO sm_cp_runtime;
