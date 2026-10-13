-- Plan 015 interim behavior: instance creation validates that the control-plane Connection is active, but does not
-- require its data-plane runtime projection yet. The projection gate is controlled by ConnectionRuntimePolicy.
-- Target-backed placement rules remain unchanged; later runtime/apply work will restore this foreign-key gate.

ALTER TABLE sm_data.placement
    DROP CONSTRAINT placement_connection_has_runtime;
