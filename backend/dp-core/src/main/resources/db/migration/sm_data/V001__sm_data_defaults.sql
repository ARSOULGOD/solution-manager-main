-- PHASE 1 — migrations. Run by Flyway as sm_dp_migrator; EVERY migration's first statement is
-- SET ROLE sm_dp_owner (Flyway initSql). The reason, measured precisely: membership already lets
-- the migrator ALTER owner-owned objects — what SET ROLE buys is **ownership at CREATE time**, so
-- new objects are born owned by sm_dp_owner instead of by the migrator. ALTER DEFAULT PRIVILEGES
-- lives here with the object creation it governs (its executor must be the target role or a
-- member — true both for the SET ROLE'd migrator and, after Phase 0's membership grant, for the
-- installer; placement here is cohesion, not necessity).
-- Obligation (s): this exact two-phase script runs green, end to end, from a fresh PG 14 database
-- under exactly these credentials — the migration suite's own bootstrap test, so the sketch can
-- never again drift from what executes.
-- ================================================================================================

ALTER DEFAULT PRIVILEGES FOR ROLE sm_dp_owner IN SCHEMA sm_data
    GRANT SELECT ON TABLES TO sm_dp_runtime;           -- read-baseline for future tables; WRITE
                                                       -- grants stay per-table and deliberate
