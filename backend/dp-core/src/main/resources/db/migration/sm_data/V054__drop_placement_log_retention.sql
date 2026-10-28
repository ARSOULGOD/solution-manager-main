-- Plan 018 task 1: the per-placement log cap is gone. The agent no longer collects or stores MI logs; it reads the MI's own
-- /actuator/logfile on demand, so there is no on-disk cap to set, store or push (design 2026-10-28, section 6). Nothing reads or
-- writes this table any more: PlacementLogRetention, its repository and the gateway's LogRetention push are removed in the same change.
-- V053 STAYS as written. It is on the pushed branch, and the frozen-migration check (step 12, base mode) compares against the
-- upstream merge base, so editing or deleting it would be reported as a rewrite of a published migration (OR-22). A new migration drops the table instead.
-- The table holds only operator-set caps (no REST write ever existed), so dropping it loses nothing the system still uses.
DROP TABLE sm_data.placement_log_retention;

-- ROLLBACK: nothing does so automatically, and a published migration is never edited (OR-22). By hand, re-run V053's CREATE TABLE and its
-- two GRANTs (see V053__placement_log_retention.sql): that restores an EMPTY table. The caps it held are not recoverable, and nothing reads
-- them after this change.
