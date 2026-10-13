-- ================================================================================================
-- PHASE 0 — install bootstrap. Run ONCE, in the SM database, by the CLUSTER ADMINISTRATOR credential:
-- on the pinned PG 14 floor that means the BOOTSTRAP SUPERUSER (or another owner of schema
-- `public`), because the last statement below can be executed by nobody else — see its comment.
-- Every other statement needs only CREATEROLE plus database ownership; the runbook may split them
-- off to a lesser installer, but the REVOKE cannot follow. Ordered: roles, then schema, then
-- schema-level grants — everything Phase 1 assumes. Measured on PG 14: sm_dp_migrator can run none
-- of this, and PG 14's default left CREATE on schema public open to PUBLIC.
-- ================================================================================================
CREATE ROLE sm_dp_owner    NOLOGIN;
CREATE ROLE sm_dp_migrator LOGIN IN ROLE sm_dp_owner;  -- the Flyway datasource (spring.flyway.user)
CREATE ROLE sm_dp_runtime  LOGIN NOINHERIT;            -- the application datasource; NOT an owner
                                                       -- member, so SET ROLE sm_dp_owner is refused
-- LOGIN credentials (passwords / auth method) are install-runbook material (pg_hba, managed
-- secrets) — the sketch deliberately carries none, and TCP connections need them.
GRANT sm_dp_owner TO CURRENT_USER;                     -- measured on PG 14: CREATE SCHEMA
                                                       -- AUTHORIZATION requires the EXECUTOR to be a
                                                       -- member of the target role — a non-superuser
                                                       -- installer is not, until this line (a
                                                       -- superuser bypasses it; harmless for one)
CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner;
GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime;        -- measured: without it, table grants survive
                                                       -- and every runtime query still fails
REVOKE CREATE ON SCHEMA public FROM PUBLIC;            -- ⚠ only the GRANTOR can revoke: on PG 14
                                                       -- schema public is owned by the bootstrap
                                                       -- superuser with ACL `=UC/postgres`, so this
                                                       -- needs THAT superuser — under a database
                                                       -- owner or a CREATEROLE installer alike it is
                                                       -- a SILENT WARNING no-op (both measured; the
                                                       -- runtime then created public.smuggled). PG 15
                                                       -- moved public's ownership to pg_database_owner
                                                       -- and dropped the PUBLIC grant by default
                                                       -- (release notes, verified) — but the floor is
                                                       -- 14. Obligation (s) ASSERTS the outcome:
                                                       -- has_schema_privilege('sm_dp_runtime',
                                                       -- 'public','CREATE') must be false

-- ================================================================================================

-- ================================================================================================
-- PLAN 010 T2.4 — the DP secret store's schema (D-KEY, OR-30). `sm_secret` is a SEPARATE schema from
-- sm_data on purpose (OR-30: credentials in a schema apart from config and journal tables), owned by
-- the same sm_dp_owner. A migrator cannot create a schema (measured for sm_control, the comment at the
-- head of cp-core's V001__scope.sql), so Phase 0 creates it, and the TABLES are created by sm_data's V009
-- through SmDataMigrationMain, schema-qualified, as sm_dp_owner.
-- NO default read-baseline: sm_data's V001 ALTER DEFAULT PRIVILEGES is scoped to sm_data, so sm_secret
-- gets none, and it must not — a blanket SELECT on a table of wrapped keys is the wrong default. Every
-- grant on sm_secret is explicit and per table, in V009.
-- ================================================================================================
CREATE SCHEMA sm_secret AUTHORIZATION sm_dp_owner;
GRANT USAGE ON SCHEMA sm_secret TO sm_dp_runtime;

-- ================================================================================================
-- PLAN 011 P11-2.1 — the metrics store's schema (M-Q19, OR-76). `sm_metrics` is DP-owned (the CP never touches it,
-- I7) and has its OWN Flyway history, owned by the same sm_dp_owner. As for sm_data and sm_secret, a migrator cannot
-- create a schema, so Phase 0 does, and the TABLES, the partition-runway function and the first partitions are
-- created by sm_metrics' V001 through SmDataMigrationMain, schema-qualified, as sm_dp_owner. The runtime role gets
-- USAGE and nothing else here: every table grant is explicit and per table, in V001.
-- An install that already ran Phase 0 runs just these two statements (docs/runbooks/install-prerequisites.md).
-- ================================================================================================
CREATE SCHEMA sm_metrics AUTHORIZATION sm_dp_owner;
GRANT USAGE ON SCHEMA sm_metrics TO sm_dp_runtime;
