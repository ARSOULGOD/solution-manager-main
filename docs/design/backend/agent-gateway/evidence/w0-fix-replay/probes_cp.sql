\set ON_ERROR_STOP off
CREATE OR REPLACE FUNCTION pg_temp.probe(n text, expect text, stmt text, op text, client text, actor text DEFAULT '', agent text DEFAULT '') RETURNS void LANGUAGE plpgsql AS $$
DECLARE st text := 'OK';
BEGIN
  PERFORM set_config('sm.audit.operation', op, true); PERFORM set_config('sm.audit.client_address', client, true);
  PERFORM set_config('sm.audit.correlation_id', gen_random_uuid()::text, true); PERFORM set_config('sm.audit.actor', actor, true);
  PERFORM set_config('sm.audit.actor_agent', agent, true);
  BEGIN EXECUTE stmt; EXCEPTION WHEN OTHERS THEN st := SQLSTATE; END;
  RAISE NOTICE '% %  (expected %, got %)', CASE WHEN st = expect THEN 'PASS' ELSE 'FAIL' END, n, expect, st;
END $$;
SELECT session_user;
SELECT pg_temp.probe('N3 operator-cli HIERARCHY_NODE_RENAMED, no actor, on organization: accepted after VCA','OK',
  $q$UPDATE sm_control.organization SET name = name || 'x'$q$, 'HIERARCHY_NODE_RENAMED','operator-cli');
SELECT pg_temp.probe('E1 the same rename from a non-operator client address is refused','OR025',
  $q$UPDATE sm_control.organization SET name = name || 'y'$q$, 'HIERARCHY_NODE_RENAMED','198.51.100.7');
SELECT pg_temp.probe('E3 AGENT_ENROLLED with no actor at all is refused','OR025',
  $q$UPDATE sm_control.organization SET name = name || 'z'$q$, 'AGENT_ENROLLED','198.51.100.7');
SELECT pg_temp.probe('E3b a stray agent actor on a principal operation is refused','OR025',
  $q$UPDATE sm_control.organization SET name = name || 'w'$q$, 'HIERARCHY_NODE_RENAMED','operator-cli','', 'a1000000-0000-4000-8000-000000000009');
SELECT pg_temp.probe('E2 operator-cli rename on another table (organization_unit insert) is refused','OR025',
  $q$INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) SELECT organization_id, gen_random_uuid(), 'u' FROM sm_control.organization LIMIT 1$q$, 'HIERARCHY_NODE_RENAMED','operator-cli');
