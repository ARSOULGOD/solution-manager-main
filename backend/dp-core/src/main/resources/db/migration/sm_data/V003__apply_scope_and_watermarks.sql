-- The admission SCOPE ROW — the serialization point AND the instance-wide generation watermark.
-- An exact lock row, deliberately NOT pg_advisory_xact_lock: advisory locks are database-wide with
-- hash-typed keys (int4 hashtext collides — measured — and even 64-bit hashing shares one unnamed
-- namespace with every other subsystem); SELECT … FOR UPDATE on this row is collision-free,
-- tenant-scoped by its own key, and visible in pg_locks with its true identity.
CREATE TABLE sm_data.apply_scope (
    tenant_id          uuid   NOT NULL,
    instance_id        uuid   NOT NULL,
    instance_watermark bigint NOT NULL DEFAULT 0
        CONSTRAINT instance_watermark_nonneg CHECK (instance_watermark >= 0),
                                               -- highest generation reaching STAGED/APPLIED/
                                               -- resolved-'applied' through an INSTANCE-WIDE request
    PRIMARY KEY (tenant_id, instance_id)
);

-- Per-placement watermarks, maintained in the same transaction that lands the state they record:
CREATE TABLE sm_data.placement_watermark (
    tenant_id    uuid   NOT NULL,
    instance_id  uuid   NOT NULL,
    placement_id uuid   NOT NULL
        CONSTRAINT real_placements_only CHECK
            (placement_id <> '00000000-0000-0000-0000-000000000000'),
                                               -- instance-wide lives on apply_scope.instance_watermark;
                                               -- the sentinel has no business here
    generation   bigint NOT NULL
        CONSTRAINT placement_watermark_positive CHECK (generation > 0),
    PRIMARY KEY (tenant_id, instance_id, placement_id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.apply_scope (tenant_id, instance_id)
);

-- Watermarks only RISE — a trigger, because the hazard is row-relative: the runtime's UPDATE right
-- reaches every row, so an unguarded statement could rewind any tenant's fence and re-admit a
-- superseded generation (measured). With this guard, an engine bug touching the wrong row can only
-- OVER-fence (fail-safe direction — an over-raised watermark refuses applies, never re-admits stale
-- ones). The trigger guards the VALUE; the row's IDENTITY is guarded by the column-scoped grant at
-- the end of this phase — the two together close both ways of moving a fence.
-- ONE FUNCTION PER TABLE, deliberately: a shared function branching on TG_TABLE_NAME does not work —
-- PL/pgSQL resolves every field reference in an IF condition against the firing row's type, so
-- `NEW.generation` raises 42703 on apply_scope and `NEW.instance_watermark` on placement_watermark:
-- measured on PG 14, three of the four directions died — every RISE on both tables (so no watermark
-- could ever be raised, any role) and the placement DECREASE, whose 42703 an earlier run had read as
-- the refusal it was asserting; only the apply_scope decrease still fired correctly. Which is
-- exactly why the harness rule below demands the refusal's NAME, not merely an error.
CREATE FUNCTION sm_data.instance_watermark_only_rises() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.instance_watermark < OLD.instance_watermark THEN
        RAISE EXCEPTION 'instance watermark may not decrease (% -> %)',
            OLD.instance_watermark, NEW.instance_watermark;
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION sm_data.placement_watermark_only_rises() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.generation < OLD.generation THEN
        RAISE EXCEPTION 'placement watermark may not decrease (% -> %)',
            OLD.generation, NEW.generation;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_scope_monotonic BEFORE UPDATE ON sm_data.apply_scope
    FOR EACH ROW EXECUTE FUNCTION sm_data.instance_watermark_only_rises();
CREATE TRIGGER placement_watermark_monotonic BEFORE UPDATE ON sm_data.placement_watermark
    FOR EACH ROW EXECUTE FUNCTION sm_data.placement_watermark_only_rises();

ALTER TABLE sm_data.apply_request ADD CONSTRAINT request_has_scope
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.apply_scope (tenant_id, instance_id);
    -- the FK promised above: no request without its lock row

GRANT SELECT, INSERT, UPDATE (instance_watermark) ON sm_data.apply_scope        TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE (generation)         ON sm_data.placement_watermark TO sm_dp_runtime;
    -- engine state, not audit: the admission transaction locks and maintains these. UPDATE is
    -- COLUMN-scoped to the watermark value alone — the identity columns (tenant_id, instance_id,
    -- placement_id) are not runtime-writable. Closes the bypass a value-only trigger leaves open:
    -- re-keying a row moves a fence to another scope without lowering any watermark, un-fencing the
    -- original scope. No engine path mutates watermark identity (a scope row is upserted by its own
    -- key and only ever raised); measured on PG 14: the column grant still admits the admission
    -- upsert, `SELECT … FOR UPDATE` (needs UPDATE on at least one column) and the raise, and
    -- refuses every identity UPDATE and DELETE.
