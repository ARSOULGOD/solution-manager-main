\set ON_ERROR_STOP off
DO $$ DECLARE r record; BEGIN
  BEGIN PERFORM * FROM sm_data.agent_gateway_maintenance(); RAISE NOTICE 'PASS D5 sm_dp_runtime can EXECUTE agent_gateway_maintenance()';
  EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'FAIL D5 (%)', SQLSTATE; END;
  BEGIN UPDATE sm_data.agent_event SET detail_code='x'; RAISE NOTICE 'FAIL D6 runtime UPDATE agent_event allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS D6 runtime UPDATE on agent_event refused 42501'; WHEN OTHERS THEN RAISE NOTICE 'FAIL D6 (%)', SQLSTATE; END;
END $$;
