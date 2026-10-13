\timing on
BEGIN;
INSERT INTO sm_data.apply_scope(tenant_id,instance_id) VALUES ('11111111-1111-4111-8111-111111111111','a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2') ON CONFLICT DO NOTHING;
SELECT instance_id FROM sm_data.apply_scope WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND instance_id='a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2' FOR UPDATE;
INSERT INTO sm_data.apply_request(apply_id,tenant_id,instance_id,placement_id,operation,generation,strategy,requested_by,requested_at,state)
VALUES ('d0000001-0000-4000-8000-000000000001','11111111-1111-4111-8111-111111111111','a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2','00000000-0000-0000-0000-000000000000','apply',5,'rolling-health-gated','op:sessA',now(),'RUNNING');
SELECT pg_sleep(6);
COMMIT;
