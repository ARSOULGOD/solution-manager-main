-- PLAN 010 T11.2 (sm_data V014; REQ-013, OR-47; docs/design/backend/fleet-apply.md section 4.3). Run by Flyway as
-- sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by sm_dp_owner. Keyed by
-- tenant_id first (I5); no foreign key leaves sm_data (I7); no secret is stored (I6): ids, generation numbers and
-- classifier names only, so there is nowhere to put a value.
--
-- The number is the plan's own: the lane built this as V015, a placeholder, and the S8-S11 merge renumbered it to the next
-- free sm_data number, V014 (V001-V013 existed; T9.3 needed none). Nothing here depends on the number.
--
-- A fleet is a DP-owned record OVER ordinary apply requests. apply_request gains nothing: every admitted member is an
-- ordinary request with its own id, journal and audit line (the audit line IS that row, D10 approval 1.4), so a fleet
-- writes no sm_control row. fleet_action is the owning row; fleet_member points at a member's request by an apply_id
-- MINTED AT SUBMIT and inserted by admission later (section 4.2 (a)), so a crash between an admission and its record
-- is recovered by admitting again under the same id, never by a heuristic search.

-- ==== 1. fleet_action - the owning row, stamped (OR-35) ============================================================
CREATE TABLE sm_data.fleet_action (
    tenant_id uuid NOT NULL,
    fleet_id uuid NOT NULL,                              -- UUIDv7, minted by the data plane
    catalog_solution_id uuid NOT NULL,                   -- REQ-013: "instances of the same solution" (section 5.1)
    strategy text NOT NULL
        CONSTRAINT fleet_strategy_known CHECK (strategy IN
            ('manual', 'rolling-health-gated', 'rolling-no-validation', 'restart-all', 'scheduled')),
                                                         -- apply_request's own list verbatim; which the API SERVES is the
                                                         -- gateway's and the service's (501), not a schema fact
    outage_confirmed_by text,
    batch_size integer NOT NULL CONSTRAINT fleet_batch_size_positive CHECK (batch_size >= 1),
    on_failure text NOT NULL CONSTRAINT fleet_on_failure_known CHECK (on_failure IN ('continue', 'halt-remaining')),
    state text NOT NULL
        CONSTRAINT fleet_state_known CHECK (state IN ('RUNNING', 'SUCCEEDED', 'COMPLETED_WITH_FAILURES', 'HALTED')),
    current_batch integer NOT NULL DEFAULT 0 CONSTRAINT fleet_current_batch_nonneg CHECK (current_batch >= 0),
    requested_by text NOT NULL,                          -- principal id, never a display name (as apply_request)
    requested_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,                        -- the submit's X-Correlation-Id
    terminal_at timestamptz,                             -- NULL exactly while RUNNING
    state_version bigint NOT NULL,                       -- OR-35: state_version_seq, stamped by trigger
    PRIMARY KEY (tenant_id, fleet_id),
    CONSTRAINT fleet_terminal_has_timestamp CHECK ((state = 'RUNNING') = (terminal_at IS NULL)),
    CONSTRAINT fleet_outage_confirmed CHECK (strategy <> 'restart-all' OR outage_confirmed_by IS NOT NULL));

-- The scheduler's one read per tenant: that tenant's RUNNING fleets. Partial, so a finished fleet costs nothing.
CREATE INDEX fleet_action_running ON sm_data.fleet_action (tenant_id, requested_at) WHERE state = 'RUNNING';

-- ==== 2. fleet_member - one row per member, refused ones included ================================================
CREATE TABLE sm_data.fleet_member (
    tenant_id uuid NOT NULL,
    fleet_id uuid NOT NULL,
    ordinal integer NOT NULL CONSTRAINT fleet_member_ordinal_nonneg CHECK (ordinal >= 0),
                                                         -- the member's index in the request, 0-based
    batch integer CONSTRAINT fleet_member_batch_nonneg CHECK (batch >= 0),
                                                         -- ordinal / batch_size; NULL for 'refused'
    instance_id uuid NOT NULL,
    placement_id uuid NOT NULL,                          -- non-refused rows: a validated live placement of instance_id.
                                                         -- 'refused' rows: the CALLER-NAMED, UNVALIDATED ids - the data
                                                         -- plane never reads a member the gateway refused, so it may name no
                                                         -- placement at all, or another organization's instance
    generation bigint NOT NULL CONSTRAINT fleet_member_generation_positive CHECK (generation > 0),
    apply_id uuid,                                       -- PRE-MINTED (section 4.2 a); NULL only for 'refused'. NO foreign key:
                                                         -- the request does not exist until admission
    disposition text NOT NULL
        CONSTRAINT fleet_member_disposition_known CHECK (disposition IN
            ('refused', 'pending', 'admitted', 'not_admitted', 'skipped')),
    refusal_code text
        CONSTRAINT fleet_member_refusal_code_known CHECK (refusal_code IN ('FORBIDDEN', 'NOT_FOUND')),
                                                         -- the two answers require() gives; set iff 'refused'
    admission_refusal text
        CONSTRAINT fleet_member_admission_refusal_grammar CHECK (admission_refusal ~ '^[A-Z][A-Z0-9_]{0,63}$'),
                                                         -- a RefusalReason or pre-admission refusal NAME, never text;
                                                         -- set iff 'not_admitted'
    settled text CONSTRAINT fleet_member_settled_known CHECK (settled IN ('succeeded', 'failed')),
                                                         -- section 5.3; written once, never changed
    PRIMARY KEY (tenant_id, fleet_id, ordinal),
    CONSTRAINT fleet_member_has_fleet FOREIGN KEY (tenant_id, fleet_id)
        REFERENCES sm_data.fleet_action (tenant_id, fleet_id),
    CONSTRAINT fleet_member_named_once UNIQUE (tenant_id, fleet_id, instance_id, placement_id),
    CONSTRAINT fleet_member_request_once UNIQUE (tenant_id, apply_id),   -- a request belongs to at most one fleet
    CONSTRAINT fleet_member_refusal_iff_refused CHECK ((disposition = 'refused') = (refusal_code IS NOT NULL)),
    CONSTRAINT fleet_member_batch_unless_refused CHECK ((disposition = 'refused') = (batch IS NULL)),
    CONSTRAINT fleet_member_apply_id_unless_refused CHECK ((disposition = 'refused') = (apply_id IS NULL)),
    CONSTRAINT fleet_member_admission_refusal_iff_not_admitted CHECK
        ((disposition = 'not_admitted') = (admission_refusal IS NOT NULL)),
    -- an admitted member gains its settlement later; a member the admission refused is settled 'failed' at once (5.3);
    -- every other disposition has none. coalesce: a NULL settled must not let a NULL CHECK admit (measured, V002).
    CONSTRAINT fleet_member_settled_fits_disposition CHECK (CASE disposition
        WHEN 'admitted' THEN true
        WHEN 'not_admitted' THEN coalesce(settled = 'failed', false)
        ELSE settled IS NULL END));

-- Section 5.6: at most one LIVE reservation per placement. A pending member, or an admitted one not yet settled, holds
-- its placement; a second fleet naming it is refused 409, structurally, as apply_request's fences refuse a second
-- in-flight apply. A settled, refused, skipped or not-admitted member holds nothing. This also makes a double-clicked
-- submit harmless: the second copy is refused, and nothing of it is written. It does NOT stop a single submitApply on
-- the placement (Q-F3 (a)): admission reads apply_request only, and the member then fails visibly at its batch.
CREATE UNIQUE INDEX fleet_member_reservation ON sm_data.fleet_member (tenant_id, instance_id, placement_id)
    WHERE disposition IN ('pending', 'admitted') AND settled IS NULL;

-- ==== 3. the stamp, and the order it is taken in (OR-35, section 4.3) ===========================================
-- ONE FUNCTION PER OWNING TABLE (V003's measured reason). fleet_action IS the stamped row: on it the row being written is
-- the lock (PostgreSQL waits for the tuple BEFORE a BEFORE ROW UPDATE trigger runs), so within one fleet stamp order is
-- commit order. Every write stamps; a service with nothing observable to change does not write.
CREATE FUNCTION sm_data.stamp_fleet_action_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER fleet_action_stamp BEFORE INSERT OR UPDATE ON sm_data.fleet_action
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_fleet_action_state_version();

-- A fleet leaves RUNNING exactly once, never goes back to an earlier batch, and its terminal_at never moves. The CHECK
-- fleet_terminal_has_timestamp makes terminal_at non-NULL exactly when the state is not RUNNING; this makes the move one-way.
-- (A state that is no state at all passes here and is refused by fleet_state_known, by name.)
CREATE FUNCTION sm_data.fleet_action_moves_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'RUNNING' AND NEW.state IS DISTINCT FROM OLD.state THEN
        RAISE EXCEPTION 'a fleet leaves RUNNING exactly once and never returns to it';
    END IF;
    IF NEW.current_batch < OLD.current_batch THEN
        RAISE EXCEPTION 'a fleet never moves back to an earlier batch';
    END IF;
    IF OLD.terminal_at IS NOT NULL AND NEW.terminal_at IS DISTINCT FROM OLD.terminal_at THEN
        RAISE EXCEPTION 'a fleet''s terminal_at is written once and never changes';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fleet_action_forward BEFORE UPDATE ON sm_data.fleet_action
    FOR EACH ROW EXECUTE FUNCTION sm_data.fleet_action_moves_forward();

-- The member guard: the forward-only rules, for every writer (the service's conditional UPDATEs are the first enforcer):
--   * a member is BORN pending, or refused; 'refused' is written only at insert and never changes;
--   * pending -> admitted | not_admitted | skipped, and nothing else leaves 'pending';
--   * an admitted member gains its settlement exactly once (admitted before it is settled, never both at once);
--   * a settlement never changes - the fleet's verdict on a member must not flip when its request moves on (a STAGED
--     request superseded by a later apply, say).
-- (A value that is no disposition at all passes every rule here and is refused by fleet_member_disposition_known, by name.)
--
-- LOCK ORDER, stated as what the database does and does not do. The rule (OR-35, section 4.3) is that a transaction changing
-- a member takes the parent fleet_action row FOR UPDATE first, then the member, then the parent. The SERVICE does exactly
-- that (FleetRows.lock before any member write). The database cannot force it for another writer: PostgreSQL has already
-- locked the member tuple before a BEFORE ROW trigger runs (V007's measured note), so a trigger's own FOR UPDATE would come
-- second anyway. What the database DOES guarantee is the property the rule exists for - stamp order is commit order within a
-- fleet: the AFTER trigger below updates the parent, which takes the parent's row lock (held to commit) BEFORE
-- fleet_action_stamp draws the next value, so two transactions changing one fleet's members draw their stamps in the order
-- they commit. Admission takes apply_scope then instance_state and never a fleet row, and the fleet holds only its own row
-- while it holds none of those, so the fleet adds no edge that could invert admission's lock order.
CREATE FUNCTION sm_data.fleet_member_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.disposition IN ('admitted', 'not_admitted', 'skipped') THEN
            RAISE EXCEPTION 'a fleet member is born pending or refused';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.disposition = 'refused' THEN
        RAISE EXCEPTION 'a refused fleet member is written only at insert and never changes';
    END IF;
    IF NEW.disposition NOT IN ('pending', 'refused', 'admitted', 'not_admitted', 'skipped') THEN
        RETURN NEW;
    END IF;
    IF OLD.settled IS NOT NULL AND NEW.settled IS DISTINCT FROM OLD.settled THEN
        RAISE EXCEPTION 'a fleet member''s settlement is written once and never changes';
    END IF;
    IF NEW.disposition = OLD.disposition THEN
        RETURN NEW;                                     -- admitted gaining settled, or a same-value write
    END IF;
    IF OLD.disposition = 'pending' AND NEW.disposition IN ('admitted', 'not_admitted', 'skipped') THEN
        IF NEW.disposition = 'admitted' AND NEW.settled IS NOT NULL THEN
            RAISE EXCEPTION 'a fleet member is admitted before it is settled';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a fleet member''s disposition only moves forward from pending';
END $$;
CREATE TRIGGER fleet_member_guard BEFORE INSERT OR UPDATE ON sm_data.fleet_member
    FOR EACH ROW EXECUTE FUNCTION sm_data.fleet_member_guard();

-- A member change is an observable change of the FLEET, and fleet_member has no state_version of its own: the parent is
-- stamped, in the same transaction, after the parent lock above. No member change can go unstamped. A no-op write of the
-- parent's own column stamps through fleet_action_stamp; the runtime role holds UPDATE on current_batch, which is all this
-- needs, and the trigger runs as the invoking role (no SECURITY DEFINER - V007's measured choice).
CREATE FUNCTION sm_data.fleet_member_stamps_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sm_data.fleet_action SET current_batch = current_batch
        WHERE tenant_id = NEW.tenant_id AND fleet_id = NEW.fleet_id;
    RETURN NULL;
END $$;
CREATE TRIGGER fleet_member_stamps_parent AFTER UPDATE ON sm_data.fleet_member
    FOR EACH ROW EXECUTE FUNCTION sm_data.fleet_member_stamps_parent();

-- ==== 4. grants - append-only in meaning ===========================================================================
-- Nothing is deletable: the fleet is part of the audit trail, like apply_request (V002). INSERT is column-scoped to
-- everything but state_version (the trigger stamps it; an explicit value is refused 42501, measured in V007). UPDATE reaches
-- only the columns a fleet legitimately changes - identity (tenant, fleet, solution, strategy, sizes, requester) and, on a
-- member, the instance, placement, generation, pre-minted id and batch, are not runtime-writable. fleet_member's INSERT
-- omits admission_refusal and settled: a member is born pending or refused, and gains both by UPDATE.
GRANT SELECT ON sm_data.fleet_action, sm_data.fleet_member TO sm_dp_runtime;
GRANT INSERT (tenant_id, fleet_id, catalog_solution_id, strategy, outage_confirmed_by, batch_size, on_failure, state,
              current_batch, requested_by, requested_at, correlation_id)
    ON sm_data.fleet_action TO sm_dp_runtime;
GRANT UPDATE (state, current_batch, terminal_at) ON sm_data.fleet_action TO sm_dp_runtime;
GRANT INSERT (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id, generation, apply_id, disposition,
              refusal_code)
    ON sm_data.fleet_member TO sm_dp_runtime;
GRANT UPDATE (disposition, admission_refusal, settled) ON sm_data.fleet_member TO sm_dp_runtime;
