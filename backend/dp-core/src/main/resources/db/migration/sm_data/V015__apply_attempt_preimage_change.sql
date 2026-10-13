-- PLAN 010 OR-108 (1): the declared, non-secret paths a retained pre-apply copy of a host's config file differs at
-- (OR-102's report) are kept WITH the attempt that found them, so the console can say which fields an operator's
-- break-glass edit changed instead of only that something did. Run by Flyway as sm_dp_migrator after
-- `SET ROLE sm_dp_owner` (initSql), so the column is born owned by sm_dp_owner. Renumbered from its lane's
-- placeholder V016 to V015 (the next free sm_data number) by the wave-2 integrator.
--
-- Evidence of PATHS, never of content (I6). The shape is the table's own refusal, not the engine's good manners:
--   {"paths": ["cache-engine.ttl", ...],   -- at most 50, each a dotted path (no space, quote, '=' or newline can fit)
--    "pathsTruncated": false,              -- more paths differed than are listed
--    "otherContentChanged": false}         -- the file also differs somewhere that is not a listed path
-- A change must say something: at least one path, or otherContentChanged. Only the two steps that assess or use a
-- pre-image can carry one.
ALTER TABLE sm_data.apply_attempt ADD COLUMN pre_image_change jsonb;

-- Every clause is coalesced and throw-free (V002's lesson): jsonb_path_exists with silent => true, never a cast or
-- jsonb_array_length ahead of a type guard, whose AND-order PostgreSQL does not promise. '$.paths[50]' exists only for a
-- 51st element, so NOT exists is "at most 50"; a path is a string in the same grammar as a replica ref.
ALTER TABLE sm_data.apply_attempt ADD CONSTRAINT pre_image_change_shape CHECK (pre_image_change IS NULL OR (
        coalesce(jsonb_typeof(pre_image_change) = 'object', false)
        AND coalesce(jsonb_typeof(pre_image_change -> 'paths') = 'array', false)
        AND coalesce(jsonb_typeof(pre_image_change -> 'pathsTruncated') = 'boolean', false)
        AND coalesce(jsonb_typeof(pre_image_change -> 'otherContentChanged') = 'boolean', false)
        AND NOT coalesce(jsonb_path_exists(pre_image_change,
            '$.keyvalue() ? (@.key != "paths" && @.key != "pathsTruncated" && @.key != "otherContentChanged")',
            '{}'::jsonb, true), false)
        AND NOT coalesce(jsonb_path_exists(pre_image_change, '$.paths[50]', '{}'::jsonb, true), false)
        AND NOT coalesce(jsonb_path_exists(pre_image_change,
            '$.paths[*] ? (!(@.type() == "string" && @ like_regex "^[A-Za-z0-9._:/@+-]{1,255}$"))',
            '{}'::jsonb, true), false)
        AND coalesce(pre_image_change -> 'paths' <> '[]'::jsonb
            OR pre_image_change -> 'otherContentChanged' = 'true'::jsonb, false)));

ALTER TABLE sm_data.apply_attempt ADD CONSTRAINT pre_image_change_only_for_rollback_steps
    CHECK (pre_image_change IS NULL OR step IN ('ROLLBACK_ASSESS', 'ROLLBACK'));

-- No grant to write: V005's table-wide SELECT, INSERT, UPDATE, DELETE on apply_attempt already covers the new column.
