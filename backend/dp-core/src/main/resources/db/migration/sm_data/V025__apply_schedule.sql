-- PLAN 012 T7 (sm_data V025; docs/design/backend/ops-501.md section 6, OPS-Q9 Sch-2, OPS-Q10 (c), OR-121, OR-122 (2), BE-D52).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so every object below is born owned by sm_dp_owner.
-- Keyed by tenant_id first (I5); no foreign key leaves sm_data (I7); no secret is stored (I6): ids, closed vocabularies, two
-- generation numbers and a window, so there is nowhere to put a value. NUMBERED V025 on purpose: V024 is plan 008 S4's slot
-- (the combined schedule, section 5); this migration assumes nothing of it and touches no object it creates.
--
-- THE SCHEDULED STRATEGY IS A MODIFIER, NOT A FIFTH WAY TO RESTART (ops-501.md section 6.1). A scheduled apply or rollback is
-- a row here until its window opens; at that moment the gateway's ScheduleFirer re-requires the submitter's verbs and the
-- data plane admits an ORDINARY apply (the same admission, fences, journal, ladder and events). Nothing in apply_request,
-- RequestState or the event catalog changes, and no fence is held while a schedule waits (Sch-1's defect): the row is
-- not a request, so a hand fix at 23:00 can still be applied and the schedule then REFUSES at fire (W5).
--
-- THE ROW'S STATES, forward only. PENDING is the only state with a future; the other four are terminal and the row never
-- changes again: ADMITTED (an ordinary apply was admitted at fire; apply_id names it), CANCELLED (an operator cancelled it
-- before it fired), MISSED (its window closed first: SM was down, or a fence stayed busy until notAfter), REFUSED (it was
-- refused at fire for a reason in the closed vocabulary below, e.g. the submitter lost the verb, W4). ADMITTED is the ONLY
-- state that ever ran anything.

-- ==== 1. apply_schedule ===========================================================================================
CREATE TABLE sm_data.apply_schedule (
    tenant_id uuid NOT NULL,
    schedule_id uuid NOT NULL,                           -- minted by the data plane
    instance_id uuid NOT NULL,
    placement_id uuid NOT NULL,                          -- v1 refuses instance-wide apply (BE-D23): a schedule names ONE live
                                                         -- placement; the nil sentinel is no placement, so the stamp trigger refuses it
    operation text NOT NULL,                             -- 'apply' | 'rollback': held by apply_schedule_names_one_target below
    -- WHAT IS SCHEDULED. An apply names the staged generation. A rollback stores its TARGET - an applyId ("undo this apply")
    -- or a generation, exactly one - and copies it forward AT FIRE (ops-501.md section 6.3, finding F12), never at submit: a
    -- copy at 14:00 would make old content the instance's desired head twelve hours early.
    generation bigint NULL
        CONSTRAINT apply_schedule_generation_positive CHECK (generation IS NULL OR generation > 0),
    rollback_apply_id uuid NULL,
    rollback_generation bigint NULL
        CONSTRAINT apply_schedule_rollback_generation_positive CHECK (rollback_generation IS NULL OR rollback_generation > 0),
    CONSTRAINT apply_schedule_names_one_target CHECK (
        (operation = 'apply' AND generation IS NOT NULL AND rollback_apply_id IS NULL AND rollback_generation IS NULL)
        OR (operation = 'rollback' AND generation IS NULL
            AND (rollback_apply_id IS NULL) <> (rollback_generation IS NULL))),
    -- The INNER strategy: one of the four the engine runs. 'scheduled' is never stored (OR-60 amended by OR-121: the token is
    -- answered 422 and the window is the schedule's own fields).
    strategy text NOT NULL
        CONSTRAINT apply_schedule_strategy CHECK (strategy IN ('manual', 'rolling-health-gated', 'rolling-no-validation', 'restart-all')),
    -- restart-all asserts a full outage (OPS-Q7 C-2): the PRINCIPAL ID of the session that confirmed it, written by the gateway.
    -- Present exactly for restart-all, mirroring apply_request's full_outage_confirmed.
    outage_confirmed_by uuid NULL,
    CONSTRAINT apply_schedule_outage_confirmed CHECK ((strategy = 'restart-all') = (outage_confirmed_by IS NOT NULL)),
    -- THE WINDOW: [not_before, not_after). The bound on how far ahead not_before may be (sm.dp.apply.schedule.max-lead, 7 days)
    -- and that it is in the future are the SERVICE's, because they depend on the clock; the order is structural.
    not_before timestamptz NOT NULL,
    not_after timestamptz NOT NULL,
    CONSTRAINT apply_schedule_window_ordered CHECK (not_after > not_before),
    requested_by uuid NOT NULL,                          -- the submitter: re-authorized at fire; the admitted apply's requested_by
    requested_at timestamptz NOT NULL,
    state text NOT NULL DEFAULT 'PENDING'
        CONSTRAINT apply_schedule_state CHECK (state IN ('PENDING', 'ADMITTED', 'CANCELLED', 'MISSED', 'REFUSED')),
    cancelled_by uuid NULL,                              -- the cancelling principal (any config.apply holder, not only the submitter)
    apply_id uuid NULL,                                  -- the ordinary apply admitted at fire
    -- The two numbers W6 says the row records. For a rollback: the generation resolved as the target AT FIRE, and the new one
    -- the copy-forward minted (above whatever was staged meanwhile). For an apply: only admitted_generation (= generation).
    target_generation bigint NULL
        CONSTRAINT apply_schedule_target_generation_positive CHECK (target_generation IS NULL OR target_generation > 0),
    admitted_generation bigint NULL
        CONSTRAINT apply_schedule_admitted_generation_positive CHECK (admitted_generation IS NULL OR admitted_generation > 0),
    CONSTRAINT apply_schedule_target_is_a_rollbacks CHECK (target_generation IS NULL OR operation = 'rollback'),
    -- A CLOSED classifier, never free text: a later reason is a reviewed migration. Names no id and no value.
    reason text NULL
        CONSTRAINT apply_schedule_reason CHECK (reason IN (
            'schedule.authority.revoked',                -- the submitter lost a verb, or is deactivated (W4)
            'schedule.target.gone',                      -- the instance is no longer active or the placement no longer live
            'schedule.generation.superseded',            -- the monotonicity fence: a newer generation reached the placement (W5)
            'schedule.generation.not-staged',            -- the generation no longer exists for this instance
            'schedule.rollback.target-not-eligible',     -- the rollback target is no longer a generation written to the placement
            'schedule.window.missed')),                  -- MISSED's only reason
    resolved_at timestamptz NULL,                        -- when it left PENDING (the clock of the firer or of the cancel)
    state_version bigint NOT NULL,                       -- OR-35: state_version_seq, stamped by trigger on insert and on every move
    PRIMARY KEY (tenant_id, schedule_id),
    CONSTRAINT apply_schedule_has_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES sm_data.instance_state (tenant_id, instance_id),
    -- Which placement belongs to which instance is the stamp trigger's (placement has no (tenant, instance, placement) key).
    CONSTRAINT apply_schedule_has_placement FOREIGN KEY (tenant_id, placement_id)
        REFERENCES sm_data.placement (tenant_id, placement_id),
    CONSTRAINT apply_schedule_has_apply FOREIGN KEY (tenant_id, apply_id)
        REFERENCES sm_data.apply_request (tenant_id, apply_id),
    -- THE STATE SHAPES. Every column is NOT NULL or tested with IS [NOT] NULL, so no clause is ever NULL (a NULL CHECK admits).
    CONSTRAINT apply_schedule_state_shape CHECK (
        (state = 'PENDING' AND resolved_at IS NULL AND apply_id IS NULL AND reason IS NULL AND cancelled_by IS NULL
            AND admitted_generation IS NULL AND target_generation IS NULL)
        OR (state = 'ADMITTED' AND resolved_at IS NOT NULL AND apply_id IS NOT NULL AND admitted_generation IS NOT NULL
            AND reason IS NULL AND cancelled_by IS NULL)
        OR (state = 'CANCELLED' AND resolved_at IS NOT NULL AND cancelled_by IS NOT NULL AND apply_id IS NULL
            AND reason IS NULL AND admitted_generation IS NULL AND target_generation IS NULL)
        OR (state IN ('MISSED', 'REFUSED') AND resolved_at IS NOT NULL AND reason IS NOT NULL AND apply_id IS NULL
            AND cancelled_by IS NULL AND admitted_generation IS NULL)),
    -- W6, held by the database and not only by the service (W3 review W3B-6): an ADMITTED rollback records the target it
    -- resolved at fire, and an ADMITTED apply's admitted generation IS the generation it scheduled. Both clauses are over
    -- columns that are NOT NULL or tested IS [NOT] NULL where they can matter, so neither can evaluate to NULL and admit.
    CONSTRAINT apply_schedule_admitted_rollback_records_target CHECK (
        state <> 'ADMITTED' OR operation <> 'rollback' OR target_generation IS NOT NULL),
    CONSTRAINT apply_schedule_admitted_apply_is_its_generation CHECK (
        state <> 'ADMITTED' OR operation <> 'apply' OR admitted_generation = generation),
    -- MISSED says its reason and only MISSED does; no other state carries it.
    CONSTRAINT apply_schedule_missed_reason CHECK ((state = 'MISSED') = coalesce(reason = 'schedule.window.missed', false)),
    -- "NEVER RUNS OUTSIDE THE WINDOW", as the database sees it: an admission is stamped inside [not_before, not_after), and a
    -- schedule is MISSED only once its window has closed. (resolved_at is the firer's own clock reading, the one that decided.)
    CONSTRAINT apply_schedule_admitted_inside_window CHECK (
        state <> 'ADMITTED' OR (resolved_at >= not_before AND resolved_at < not_after)),
    CONSTRAINT apply_schedule_missed_after_window CHECK (state <> 'MISSED' OR resolved_at >= not_after));

-- One schedule per apply: the ordinary apply an admission produced is claimed by at most one row, so a bug (or a bypassed
-- lock) that fired a schedule twice would be refused by the database before it could double-claim.
CREATE UNIQUE INDEX apply_schedule_apply_once ON sm_data.apply_schedule (tenant_id, apply_id) WHERE apply_id IS NOT NULL;
-- THE FIRER'S SCAN: PENDING rows by the moment their window opens. Partial, so it holds only what can still fire; terminal
-- history (never deleted) costs it nothing. Serves the one cross-organization enumeration (OR-122 (2)) and each tenant's due().
CREATE INDEX apply_schedule_due ON sm_data.apply_schedule (not_before, tenant_id) WHERE state = 'PENDING';
-- listApplySchedules: one instance's schedules, newest first.
CREATE INDEX apply_schedule_by_instance ON sm_data.apply_schedule (tenant_id, instance_id, requested_at DESC, schedule_id);

-- ==== 2. the stamp (OR-35) =========================================================================================
-- ONE FUNCTION PER OWNING TABLE (V003's measured reason). An INSERT and an UPDATE both take the owning instance_state row FOR
-- UPDATE and then the next value of the one sequence (the recorded commit-order rule: within one instance, stamp order is
-- commit order). A schedule is BORN PENDING (the INSERT grant carries no state column, and this refuses it too), and its
-- placement must be one OF THIS instance: the foreign keys alone would accept any declared instance with any placement.
-- Refusals carry the constraint name the foreign key would, so the service's mapping needs one spelling.
CREATE FUNCTION sm_data.stamp_apply_schedule_state_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM sm_data.instance_state
        WHERE tenant_id = NEW.tenant_id AND instance_id = NEW.instance_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'apply_schedule names an instance the data plane has not declared'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'apply_schedule_has_instance';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PENDING' THEN
            RAISE EXCEPTION 'an apply_schedule is inserted PENDING and moves forward only by update'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'apply_schedule_born_pending';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM sm_data.placement
                       WHERE tenant_id = NEW.tenant_id AND placement_id = NEW.placement_id
                         AND instance_id = NEW.instance_id) THEN
            RAISE EXCEPTION 'apply_schedule names a placement the instance does not have'
                USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'apply_schedule_has_placement';
        END IF;
    END IF;
    NEW.state_version := nextval('sm_data.state_version_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER apply_schedule_stamp BEFORE INSERT OR UPDATE ON sm_data.apply_schedule
    FOR EACH ROW EXECUTE FUNCTION sm_data.stamp_apply_schedule_state_version();

-- ==== 3. forward only, once ========================================================================================
-- An update must MOVE a PENDING row to another state and change nothing the request is made of: the five terminal states
-- never change again, and no update can rewrite the window, the target, the strategy, the confirmation or the submitter after
-- the fact (the audit record would otherwise be editable). The stamp is excluded because the stamp trigger rewrites it on the
-- same update. A trigger, not only the column grant: the grant guards the runtime role, this guards every other writer, the
-- owner included.
CREATE FUNCTION sm_data.apply_schedule_moves_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'PENDING' THEN
        RAISE EXCEPTION 'an apply_schedule that has left PENDING never changes again';
    END IF;
    IF NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'an apply_schedule update must move it out of PENDING';
    END IF;
    IF (to_jsonb(NEW) - 'state' - 'cancelled_by' - 'apply_id' - 'target_generation' - 'admitted_generation' - 'reason'
            - 'resolved_at' - 'state_version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'state' - 'cancelled_by' - 'apply_id' - 'target_generation'
            - 'admitted_generation' - 'reason' - 'resolved_at' - 'state_version') THEN
        RAISE EXCEPTION 'an apply_schedule may change only how it was resolved';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_schedule_forward BEFORE UPDATE ON sm_data.apply_schedule
    FOR EACH ROW EXECUTE FUNCTION sm_data.apply_schedule_moves_forward();

-- ==== 4. grants ====================================================================================================
-- INSERT is column-scoped to what a PENDING request is made of: no state (the DEFAULT 'PENDING' applies), no resolution
-- column, and no state_version (the trigger stamps it; an explicit value is refused 42501, measured in V007). UPDATE reaches
-- exactly the columns a resolution writes. No DELETE: a schedule is history, kept forever, like apply_request.
GRANT SELECT ON sm_data.apply_schedule TO sm_dp_runtime;
GRANT INSERT (tenant_id, schedule_id, instance_id, placement_id, operation, generation, rollback_apply_id,
              rollback_generation, strategy, outage_confirmed_by, not_before, not_after, requested_by, requested_at)
    ON sm_data.apply_schedule TO sm_dp_runtime;
GRANT UPDATE (state, cancelled_by, apply_id, target_generation, admitted_generation, reason, resolved_at)
    ON sm_data.apply_schedule TO sm_dp_runtime;

-- LOCK CLASS: creates objects nothing references yet (a new table, its indexes and two trigger functions); it takes no lock
-- on an existing table beyond the brief SHARE ROW EXCLUSIVE a foreign key's creation takes on instance_state, placement and
-- apply_request (CREATE TABLE ... REFERENCES). PostgreSQL holds that lock until the migration's transaction commits, not for
-- the statement; it blocks no read, and the migration is brief and runs before the binary starts. The new binary does not go
-- Ready on a schema older than this migration (ApplySchedulePersistenceConfiguration's readiness stage).
