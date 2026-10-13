-- sm_data: agent retention, the OR-77 shape (plan 015 T1.1 / T4.5; AGW-D31; central item 47; OR-130 F-5; OR-134). Number V029
-- (measured 2026-10-09 on 2568862f: V028 is the agent gateway's state); RENUMBERED V044 by OR-147 (V043 is now that state).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so the function is born owned by sm_dp_owner.
--
-- Owned by sm_dp_owner, SECURITY DEFINER, no arguments, a pinned search_path ending in pg_temp (PostgreSQL 14, CREATE FUNCTION,
-- "Writing SECURITY DEFINER Functions Safely", fetched 2026-10-09: "the temporary-table schema ... is searched first by default
-- ... write pg_temp as the last entry in search_path"; R-22), every catalog relation schema-qualified, PUBLIC revoked and EXECUTE
-- granted to the runtime role in this same transactional migration. The retention interval is a constant INSIDE the function, so no
-- caller input reaches a DELETE (OR-77's narrow I5 carve-out, extended by name to agent_command retention by OR-130 F-5).
--
-- WHAT IT DELETES: agent_command rows in a final state (ACKED / REFUSED / SUPERSEDED) older than 30 days, EXCEPT the highest
-- wire_generation of each (agent, placement) -- the agent's journal refuses any generation below the highest it has seen and
-- replays the stored outcome of one it holds, so a generation number must never be reused (review R-1).
--
-- ALSO DELETES (OR-134, F-5 scope; plan 015 section 10 item 18): agent_lease rows whose state is not ACTIVE and whose expires_at
-- is more than 30 days ago (a row is written on every lease issue and renewal). An ACTIVE lease is never deleted, however old.
--
-- WHAT IT NEVER DELETES: a live command; agent_event (OR-130 F-5 PERMITS its retention, but AGW-D31 -- "permitted is not
-- required" -- does not ask for it: this function never deletes it); agent_identity; agent_discovery (a tombstone is the
-- contract that stops re-registration); and agent_session: OR-134 keeps sessions, because there is one row per agent and keeping
-- it keeps the fencing epoch monotonic for the agent's whole life (the draft's session DELETE is removed, not held back).
--
-- LOCK CLASS: CREATE FUNCTION and GRANT lock no table. Each RUN of the function takes ROW EXCLUSIVE on sm_data.agent_command
-- and sm_data.agent_lease for its DELETEs, under lock_timeout = 5s and a non-blocking advisory lock (a concurrent run is a no-op).
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). The function holds no data and
-- nothing references it, so by hand and at any time: DROP FUNCTION sm_data.agent_gateway_maintenance(); then delete the V044
-- row from flyway_schema_history. Rows it already deleted are not restored.
CREATE FUNCTION sm_data.agent_gateway_maintenance() RETURNS TABLE (commands_deleted bigint, leases_deleted bigint)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = sm_data, pg_temp
    SET lock_timeout = '5s'
AS $$
DECLARE
    nc bigint;
    nl bigint;
BEGIN
    IF NOT pg_try_advisory_xact_lock(hashtextextended('sm.agent-gateway-maintenance', 0)) THEN
        RETURN QUERY SELECT 0::bigint, 0::bigint;                           -- a concurrent run holds it: a no-op, never a race
        RETURN;
    END IF;
    DELETE FROM sm_data.agent_command c
        WHERE c.state IN ('ACKED', 'REFUSED', 'SUPERSEDED')
          AND coalesce(c.acked_at, c.created_at) < now() - interval '30 days'
          AND EXISTS (SELECT 1 FROM sm_data.agent_command n
                       WHERE n.tenant_id = c.tenant_id AND n.agent_id = c.agent_id AND n.placement_id = c.placement_id
                         AND n.wire_generation > c.wire_generation);
    GET DIAGNOSTICS nc = ROW_COUNT;
    DELETE FROM sm_data.agent_lease
        WHERE state <> 'ACTIVE' AND expires_at < now() - interval '30 days';
    GET DIAGNOSTICS nl = ROW_COUNT;
    RETURN QUERY SELECT nc, nl;
END $$;
REVOKE ALL ON FUNCTION sm_data.agent_gateway_maintenance() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sm_data.agent_gateway_maintenance() TO sm_dp_runtime;
