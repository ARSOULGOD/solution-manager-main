-- The target metadata projection is updated when a linked target is edited. Credentials and SSH connection
-- records remain separate and are not writable through this projection.
GRANT UPDATE (connection_id, runtime, settings, retired_at) ON TABLE sm_data.deployment_target_runtime TO sm_dp_runtime;
