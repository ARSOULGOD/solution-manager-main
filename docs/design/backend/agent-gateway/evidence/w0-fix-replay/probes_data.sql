\set ON_ERROR_STOP off
SET ROLE sm_dp_owner;
CREATE OR REPLACE FUNCTION pg_temp.probe(n text, expect text, stmt text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE st text := 'OK';
BEGIN
  BEGIN EXECUTE stmt; EXCEPTION WHEN OTHERS THEN st := SQLSTATE; END;
  RAISE NOTICE '% %  (expected %, got %)', CASE WHEN st = expect THEN 'PASS' ELSE 'FAIL' END, n, expect, st;
END $$;
-- N1 legacy row survived VDA
DO $$ BEGIN RAISE NOTICE '% N1 legacy SSH row with NULL host_key_algorithm survives VDA', CASE WHEN (SELECT count(*) FROM sm_data.target_connection WHERE transport='ssh-docker' AND host_key_algorithm IS NULL AND host='legacy.example')=1 THEN 'PASS' ELSE 'FAIL' END; END $$;
SELECT pg_temp.probe('N2 agent row with host_key_algorithm set is refused','23514', $q$
 INSERT INTO sm_data.target_connection (tenant_id,target_id,transport,agent_id,allowed_config_root,allowed_container_prefix,created_by,created_at,host_key_algorithm)
 VALUES ('11111111-1111-4111-8111-111111111111','a0000000-0000-4000-8000-000000000001','agent','a1000000-0000-4000-8000-000000000001','/opt/sm','cache-','44444444-4444-4444-8444-444444444444',now(),'ssh-ed25519')$q$);
SELECT pg_temp.probe('D1 ssh-docker row without host is refused','23514', $q$
 INSERT INTO sm_data.target_connection (tenant_id,target_id,transport,port,ssh_user,host_key_fingerprint,credential_ref,allowed_config_root,allowed_container_prefix,created_by,created_at)
 VALUES ('11111111-1111-4111-8111-111111111111','a0000000-0000-4000-8000-000000000002','ssh-docker',22,'sm','SHA256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','33333333-3333-4333-8333-333333333333','/opt/sm','cache-','44444444-4444-4444-8444-444444444444',now())$q$);
SELECT pg_temp.probe('D2 agent row with an SSH host is refused','23514', $q$
 INSERT INTO sm_data.target_connection (tenant_id,target_id,transport,agent_id,host,allowed_config_root,allowed_container_prefix,created_by,created_at)
 VALUES ('11111111-1111-4111-8111-111111111111','a0000000-0000-4000-8000-000000000003','agent','a1000000-0000-4000-8000-000000000003','h.example','/opt/sm','cache-','44444444-4444-4444-8444-444444444444',now())$q$);
SELECT pg_temp.probe('D4a ssh row with a valid algorithm is accepted','OK', $q$
 INSERT INTO sm_data.target_connection (tenant_id,target_id,transport,host,port,ssh_user,host_key_fingerprint,credential_ref,allowed_config_root,allowed_container_prefix,created_by,created_at,host_key_algorithm)
 VALUES ('11111111-1111-4111-8111-111111111111','a0000000-0000-4000-8000-000000000004','ssh-docker','h2.example',22,'sm','SHA256:cccccccccccccccccccccccccccccccccccccccccccc','33333333-3333-4333-8333-333333333333','/opt/sm','cache-','44444444-4444-4444-8444-444444444444',now(),'ssh-ed25519')$q$);
SELECT pg_temp.probe('D4b ssh row with an unknown algorithm is refused','23514', $q$
 INSERT INTO sm_data.target_connection (tenant_id,target_id,transport,host,port,ssh_user,host_key_fingerprint,credential_ref,allowed_config_root,allowed_container_prefix,created_by,created_at,host_key_algorithm)
 VALUES ('11111111-1111-4111-8111-111111111111','a0000000-0000-4000-8000-000000000005','ssh-docker','h3.example',22,'sm','SHA256:dddddddddddddddddddddddddddddddddddddddddddd','33333333-3333-4333-8333-333333333333','/opt/sm','cache-','44444444-4444-4444-8444-444444444444',now(),'ssh-dsa')$q$);
-- D3 agent target + identity in one transaction; no target_host_key row
BEGIN;
INSERT INTO sm_data.target_connection (tenant_id,target_id,transport,agent_id,allowed_config_root,allowed_container_prefix,created_by,created_at)
 VALUES ('11111111-1111-4111-8111-111111111111','a0000000-0000-4000-8000-000000000006','agent','a1000000-0000-4000-8000-000000000006','/opt/sm','cache-','44444444-4444-4444-8444-444444444444',now());
INSERT INTO sm_data.agent_identity (tenant_id,agent_id,environment_id,catalog_solution_id,solution,declared_package_version,target_id,certificate_thumbprint,agent_epoch,fleet_epoch,created_at)
 VALUES ('11111111-1111-4111-8111-111111111111','a1000000-0000-4000-8000-000000000006','e0000000-0000-4000-8000-000000000001','c0000000-0000-4000-8000-000000000001','cache','1.0','a0000000-0000-4000-8000-000000000006',repeat('a',64),1,1,now());
COMMIT;
DO $$ BEGIN RAISE NOTICE '% D3 agent target commits and writes no target_host_key row', CASE WHEN (SELECT count(*) FROM sm_data.target_host_key WHERE target_id='a0000000-0000-4000-8000-000000000006')=0 AND (SELECT count(*) FROM sm_data.target_connection WHERE target_id='a0000000-0000-4000-8000-000000000006')=1 THEN 'PASS' ELSE 'FAIL' END; END $$;
SELECT pg_temp.probe('D3b the legacy SSH row still recorded its pinned key (V012 trigger intact)','OK', $q$ SELECT 1/ (SELECT count(*) FROM sm_data.target_host_key WHERE target_id='22222222-2222-4222-8222-222222222222') $q$);
SELECT pg_temp.probe('D7 agent_id is globally unique in agent_identity','23505', $q$
 INSERT INTO sm_data.agent_identity (tenant_id,agent_id,environment_id,catalog_solution_id,solution,declared_package_version,target_id,certificate_thumbprint,agent_epoch,fleet_epoch,created_at)
 VALUES ('55555555-5555-4555-8555-555555555555','a1000000-0000-4000-8000-000000000006','e0000000-0000-4000-8000-000000000001','c0000000-0000-4000-8000-000000000001','cache','1.0','a0000000-0000-4000-8000-000000000006',repeat('b',64),1,1,now())$q$);
SELECT pg_temp.probe('D8 no agent table has a bytea column','OK', $q$ SELECT 1/(CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='sm_data' AND table_name LIKE 'agent\_%' AND data_type='bytea') THEN 0 ELSE 1 END) $q$);
RESET ROLE;
