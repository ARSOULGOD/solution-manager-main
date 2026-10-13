-- sm_data: what an agent said about itself at SessionOpen (plan 015 T2.1b fix round; T2.1a amendment 656d01c5). Number V030
-- (V028 and V029 were published and frozen, OR-22; sm_data's latest was V029); RENUMBERED V045 by OR-147 (after V043, V044).
-- Run by Flyway as sm_dp_migrator after `SET ROLE sm_dp_owner` (initSql), so the columns are born on a table owned by sm_dp_owner.
--
-- Adds two columns to sm_data.agent_session:
--   features        text[] NOT NULL DEFAULT '{}'  the wire features the agent declared (discovery, rematerialize, declarations);
--                   a closed vocabulary (CHECK), lowercase, mapped from AgentFeature in code. AgentSessionPort.featuresOf reads it.
--   clock_offset_ms bigint NULL                   the DP clock minus the agent's clock at the last SessionOpen / heartbeat, in
--                   milliseconds, CLAMPED IN CODE; NULL means not measured. last_seen_at stays on the DP clock (OR-130 F-6, OR-134).
-- Nothing else: no supersedes, ack-message, container-start or request-id column is added (V043's column set stands).
--
-- GRANTS: agent_session keeps its table-level INSERT from V043, and PostgreSQL 14 (GRANT, fetched 2026-10-10) says a user may
-- INSERT "on a column if they hold that privilege for either the specific column or its whole table"; the page does not say
-- in so many words that a table-level grant reaches a column added later, so INSERT is not relied on for the new columns'
-- UPDATE: the two new columns get their own column-scoped UPDATE grant here, in V043's style.
--
-- LOCK CLASS: ALTER TABLE ... ADD COLUMN takes ACCESS EXCLUSIVE on sm_data.agent_session (PostgreSQL 14, ALTER TABLE, fetched
-- 2026-10-10: "An ACCESS EXCLUSIVE lock is acquired unless explicitly noted", and ADD COLUMN is not noted). With a non-volatile
-- DEFAULT the same page says "the default is evaluated at the time of the statement and the result stored in the table's
-- metadata ... In neither case is a rewrite of the table required"; the page names no version, and the table is one row per agent,
-- so the lock is held for catalog work only. The CHECK is validated against existing rows (all '{}'), also trivial.
--
-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand: ALTER TABLE
-- sm_data.agent_session DROP COLUMN features, DROP COLUMN clock_offset_ms; then delete the V045 row from flyway_schema_history.
-- Dropping loses what agents declared, which the next SessionOpen writes again.
ALTER TABLE sm_data.agent_session
    ADD COLUMN features text[] NOT NULL DEFAULT '{}'
        CONSTRAINT agent_session_features CHECK (features <@ ARRAY['discovery', 'rematerialize', 'declarations']::text[]),
    ADD COLUMN clock_offset_ms bigint NULL;

GRANT UPDATE (features, clock_offset_ms) ON sm_data.agent_session TO sm_dp_runtime;
