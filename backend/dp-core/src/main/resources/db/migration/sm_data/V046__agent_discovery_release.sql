-- V046 (written V031; renumbered by OR-147) (plan 015 T4.1, AGW-D30, P15-9): the agent discovery tombstone's two missing rules -- who WRITES it when an instance ends,
-- and the release of a REFUSED or ABANDONED key.
--
-- 1. THE TOMBSTONE WRITE (a trigger on placement.retired_at). AGW-D30 says the tombstone is set "in the same DP transaction" as
--    the undeclare, the placement retirement and the BE-D32 sweep's abandonment. Those are three services (InventoryService,
--    InstanceRetirementServiceImpl, the sweep) and a fourth caller would have to remember it; an application hook per service
--    also widens InventoryService's canonical constructor, which the arch-tests pin. A placement's retirement is the one event
--    all of them cause, so the rule lives where it cannot be bypassed: AFTER UPDATE OF retired_at on sm_data.placement, when
--    retired_at goes NULL -> a value, it moves the agent_discovery row that names the placement (placement_id), and only a
--    PENDING or REGISTERED one, by the lifecycle the instance has AT THAT MOMENT:
--        'abandoned'            -> ABANDONED   (the sweep, or a declare's refusal: abandon() marks the instance before it retires
--                                               the placements; only a PENDING row -- a REGISTERED instance is never abandoned)
--        'retiring' / 'retired' -> UNDECLARED  (undeclare's completeRetire retires the placements while the instance is 'retiring')
--        anything else          -> RETIRED     (retirePlacement on a live instance)
--    The trigger runs as the invoking role, which holds UPDATE (state, settled_at) on agent_discovery (V043); it needs no
--    privilege the runtime role lacks. A row that is not PENDING or REGISTERED (REFUSED by the completer first, say) is untouched.
--    The REFUSED write of a completer's refusal is made BEFORE it abandons the instance, so this trigger finds no PENDING row.
--
-- 2. THE RELEASE. V043 made REFUSED, ABANDONED, UNDECLARED and RETIRED terminal for every writer and gave the runtime role no
--    DELETE. The tombstone contract also says REFUSED and ABANDONED are released "when the container is RE-CREATED (a new
--    container_id under the key)": an operator who fixes what made the DP refuse a container (a prefix, a mount) recreates it,
--    and the DP must try again. V043 left that move unwritable. This replaces the terminal-guard FUNCTION's body (the trigger
--    stays) so that exactly one move out of a terminal state is legal:
--        REFUSED or ABANDONED  ->  PENDING, in the SAME statement that records a DIFFERENT container_id.
--    UNDECLARED and RETIRED have NO release here: they leave only through a change of the label value, which is a different
--    KEY (a new row), never an update of this one (a CI/CD redeploy re-creating the container must not re-register what an
--    operator removed). Every other change of state out of a terminal state is still refused, with V043's message.
--
-- 2b. THE GRANT. The release restamps first_seen_at (the BE-D32 window the completer is held to is measured from it), and V043's UPDATE
--    list for sm_dp_runtime does not name that column: it is added here, and only it.
GRANT UPDATE (first_seen_at) ON sm_data.agent_discovery TO sm_dp_runtime;
--
-- ORDER COUPLING (the trigger infers WHY a placement was retired from the instance's lifecycle at that moment): InventoryService.abandon
-- and settlePending mark the instance 'abandoned' BEFORE they retire its placements, and InstanceRetirementServiceImpl.completeRetire
-- retires the placements while the instance is still 'retiring' (markRetired comes after). Reordering either silently turns a
-- tombstone into RETIRED, which can never be released; the callers carry a comment and AgentDiscoveryIT pins each path.
--
-- 3. THE INDEX. The trigger's UPDATE finds the row by (tenant_id, placement_id), which the primary key (tenant, agent, label, container)
--    cannot serve, and agent_discovery_has_placement (V043) has no supporting index: without this, every placement retirement of the
--    tenant (dp-remote's included) scans every tombstone it holds, and tombstones only accumulate.
--
-- LOCK CLASS: CREATE OR REPLACE FUNCTION, CREATE FUNCTION and CREATE TRIGGER ... ON sm_data.placement take SHARE ROW EXCLUSIVE on
-- placement (CREATE TRIGGER, PostgreSQL 14) for the instant of the statement: no row is rewritten or scanned. CREATE INDEX takes SHARE
-- on sm_data.agent_discovery (blocks its writes, not its reads) while it builds: the table holds one row per container an agent ever
-- reported and, before the first enrolment, none. Run by Flyway as
-- sm_dp_migrator after SET ROLE sm_dp_owner (initSql).
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand and at any time: DROP
-- TRIGGER placement_discovery_tombstone ON sm_data.placement; DROP INDEX sm_data.agent_discovery_by_placement; DROP FUNCTION sm_data.agent_discovery_tombstone_on_retirement();
-- put back V043's body of sm_data.agent_discovery_terminal_is_final() (its first IF alone, without the release exception); then
-- delete the V046 row from flyway_schema_history. A row already released stays PENDING; a tombstone already written stays.

CREATE INDEX agent_discovery_by_placement ON sm_data.agent_discovery (tenant_id, placement_id) WHERE placement_id IS NOT NULL;

CREATE FUNCTION sm_data.agent_discovery_tombstone_on_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_lifecycle text;
BEGIN
    IF OLD.retired_at IS NULL AND NEW.retired_at IS NOT NULL THEN
        SELECT lifecycle INTO v_lifecycle FROM sm_data.instance_state
         WHERE tenant_id = NEW.tenant_id AND instance_id = NEW.instance_id;
        UPDATE sm_data.agent_discovery
           SET state = CASE WHEN v_lifecycle = 'abandoned' THEN 'ABANDONED'
                            WHEN v_lifecycle IN ('retiring', 'retired') THEN 'UNDECLARED'
                            ELSE 'RETIRED' END,
               settled_at = NEW.retired_at
         WHERE tenant_id = NEW.tenant_id AND placement_id = NEW.placement_id
           AND ((v_lifecycle = 'abandoned' AND state = 'PENDING')
                OR (v_lifecycle IS DISTINCT FROM 'abandoned' AND state IN ('PENDING', 'REGISTERED')));
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER placement_discovery_tombstone AFTER UPDATE OF retired_at ON sm_data.placement
    FOR EACH ROW EXECUTE FUNCTION sm_data.agent_discovery_tombstone_on_retirement();

CREATE OR REPLACE FUNCTION sm_data.agent_discovery_terminal_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('REFUSED', 'ABANDONED', 'UNDECLARED', 'RETIRED') AND NEW.state IS DISTINCT FROM OLD.state THEN
        IF OLD.state IN ('REFUSED', 'ABANDONED') AND NEW.state = 'PENDING'
           AND NEW.container_id IS DISTINCT FROM OLD.container_id THEN
            RETURN NEW;                  -- the release: the container under the key was re-created
        END IF;
        RAISE EXCEPTION 'a terminal agent discovery state (REFUSED, ABANDONED, UNDECLARED, RETIRED) is final'
            USING ERRCODE = 'check_violation', COLUMN = 'state';
    END IF;
    RETURN NEW;
END $$;
