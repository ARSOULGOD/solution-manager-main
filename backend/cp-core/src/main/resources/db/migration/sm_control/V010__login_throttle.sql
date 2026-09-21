CREATE TABLE sm_control.login_throttle (
    subject_hash text PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    failure_count integer NOT NULL,
    locked_until timestamptz,
    CONSTRAINT login_throttle_subject_hash_shape CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT login_throttle_failure_count_nonnegative CHECK (failure_count >= 0)
);

REVOKE ALL PRIVILEGES ON TABLE sm_control.login_throttle FROM sm_cp_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE sm_control.login_throttle TO sm_cp_runtime;
