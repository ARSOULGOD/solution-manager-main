\timing on
BEGIN;
INSERT INTO sm_data.apply_scope(tenant_id,instance_id) VALUES ('22222222-2222-4222-8222-222222222222','a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2') ON CONFLICT DO NOTHING;
SELECT instance_id FROM sm_data.apply_scope WHERE tenant_id='22222222-2222-4222-8222-222222222222' AND instance_id='a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2' FOR UPDATE;
INSERT INTO sm_data.apply_request(apply_id,tenant_id,instance_id,placement_id,operation,generation,strategy,requested_by,requested_at,state)
VALUES ('d0000002-0000-4000-8000-000000000002','22222222-2222-4222-8222-222222222222','a2a2a2a2-2222-4222-8222-a2a2a2a2a2a2','b1b1b1b1-1111-4111-8111-b1b1b1b1b1b1','apply',5,'rolling-health-gated','op:sessA2',now(),'RUNNING');
SELECT pg_sleep(5);
COMMIT;
