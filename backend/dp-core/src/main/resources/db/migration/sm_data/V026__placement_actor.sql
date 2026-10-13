-- OR-125 (1) / W2A-1 (sm_data V026; plan 008 S3, BE-D47, BE-D53). Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner`
-- (initSql), so everything below is born owned by sm_dp_owner. Keyed by tenant_id first (I5); no foreign key leaves
-- sm_data (I7); nothing here can hold a secret (I6): two actor ids.
--
-- WHY. addPlacement and retirePlacement took the acting principal and then threw it away ("carried, not recorded", W2 review
-- W2A-1): a placement change was the one inventory change nobody could be named for. The owner ruled it recorded (OR-125 (1)).
--
-- THE SHAPE: two nullable columns on the row, not a placement-event table. A placement is added once and retired at most
-- once, so each fact is exactly one value; a second table would repeat the key, need its own grants, trigger and
-- tenant-scoped read, and the "who" is still needed in the same transaction that writes the row.
--   created_by  uuid  the actor that added the placement (addPlacement; the declare's own placement is the declaring principal)
--   retired_by  uuid  the actor that retired it (retirePlacement), written with retired_at and never after
-- An ACTOR id, not a user id: the agent gateway (plan 015) writes an agent's id into the same two columns for the writes it
-- makes itself (OR-128 (5)); no kind column is needed because the id space is uuid for users, agents and sessions alike.
--
-- NULL MEANS "NOT ATTRIBUTED", never a guess. Every existing row stays NULL: the principal that acted before this migration
-- was discarded and cannot be recovered. A retirement made by a sweep or by an instance retirement (retireAll: the abandon
-- sweep, undeclare) is not one principal's act on one placement and also leaves retired_by NULL. The columns are NOT NULL
-- on no row, on purpose, so this migration needs no back-fill and rewrites no data.
ALTER TABLE sm_data.placement ADD COLUMN created_by uuid NULL;
ALTER TABLE sm_data.placement ADD COLUMN retired_by uuid NULL;
ALTER TABLE sm_data.placement ADD CONSTRAINT placement_retired_by_needs_retirement
    CHECK (retired_by IS NULL OR retired_at IS NOT NULL);

-- Immutability stays total but for the one legal change, which now sets retired_by WITH retired_at. created_by is never in
-- any grant and the trigger refuses it to every other writer too (the superuser bypasses the grant, not the trigger).
CREATE OR REPLACE FUNCTION sm_data.placement_immutable_except_retirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.retired_at IS NOT NULL
       OR NEW.retired_at IS NULL
       OR (to_jsonb(NEW) - 'retired_at' - 'retired_by') IS DISTINCT FROM (to_jsonb(OLD) - 'retired_at' - 'retired_by') THEN
        RAISE EXCEPTION 'a placement is immutable: the only legal change is retiring it once';
    END IF;
    RETURN NEW;
END $$;

-- SELECT and INSERT are table-level (V007), so created_by is insertable and readable already; the UPDATE grant widens by
-- exactly one column, and retired_at stays grantable on its own (the sweep writes it without an actor).
GRANT UPDATE (retired_by) ON sm_data.placement TO sm_dp_runtime;

-- ROLLBACK (not run by Flyway; the migration is forward-only once published): ALTER TABLE sm_data.placement DROP CONSTRAINT
-- placement_retired_by_needs_retirement; REVOKE UPDATE (retired_by) ON sm_data.placement FROM sm_dp_runtime; restore V007's
-- function body (CREATE OR REPLACE with `to_jsonb(NEW) - 'retired_at'`); ALTER TABLE sm_data.placement DROP COLUMN retired_by,
-- DROP COLUMN created_by.
