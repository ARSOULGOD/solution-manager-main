-- Linked target metadata is mutable through the target.edit path. The runtime role still cannot change
-- identity, ownership, creation or retirement columns.
GRANT UPDATE (name, connection_id, runtime, runtime_settings) ON TABLE sm_control.target TO sm_cp_runtime;
