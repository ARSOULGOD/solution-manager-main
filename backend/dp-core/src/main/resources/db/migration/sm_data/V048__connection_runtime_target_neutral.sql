-- Remote Connection access is not a deployment target. Its placement envelope is supplied by a target.
ALTER TABLE sm_data.connection_runtime ALTER COLUMN allowed_config_root DROP NOT NULL;
ALTER TABLE sm_data.connection_runtime ALTER COLUMN allowed_container_prefix DROP NOT NULL;
ALTER TABLE sm_data.connection_runtime DROP CONSTRAINT connection_runtime_config_root;
ALTER TABLE sm_data.connection_runtime DROP CONSTRAINT connection_runtime_container_prefix;
