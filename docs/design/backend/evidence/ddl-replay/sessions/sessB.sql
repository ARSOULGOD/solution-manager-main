\timing on
\set ON_ERROR_STOP 0
BEGIN;
INSERT INTO sm_data.apply_scope(tenant_id,instance_id) VALUES ('11111111-1111-4111-8111-111111111111','a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2') ON CONFLICT DO NOTHING;
SELECT instance_id FROM sm_data.apply_scope WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND instance_id='a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2' FOR UPDATE;
SELECT count(*) AS instance_wide_inflight FROM sm_data.apply_request
 WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND instance_id='a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2' AND placement_id='00000000-0000-0000-0000-000000000000'
   AND state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE') AND operation <> 'stop';
COMMIT;
