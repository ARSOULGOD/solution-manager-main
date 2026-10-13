-- OR-120 / OR-123: organizations, sub-organizations (organization_unit) and environments carry a display name.
-- Never edit this file once applied (OR-22): a correction is a new migration, never an edit here.
--
-- Existing rows are back-filled with their id as text (the value the API returned as a placeholder until now),
-- then the column becomes NOT NULL with a shape CHECK: 1-100 characters, no control characters, not blank.
-- The ids stay the immutable surrogate keys (D11-AMENDMENT-PROPOSAL.md section 2); the name is mutable display text.
--
-- There is deliberately NO default and NO trigger: a row inserted without a name is refused by NOT NULL, so an
-- inserter that forgets the name fails loudly instead of getting a UUID label. Only rows that existed before this
-- migration (V007's install organization among them) carry their id's text, until they are renamed. Creating and
-- renaming nodes through the API is OR-114 item (3); any default belongs in that application path.
--
-- Privileges: sm_cp_runtime already holds table-level SELECT on all three tables (V008), which covers the new
-- column. It gains no write privilege: no runtime path creates or renames these rows.

ALTER TABLE sm_control.organization      ADD COLUMN name text;
ALTER TABLE sm_control.organization_unit ADD COLUMN name text;
ALTER TABLE sm_control.environment       ADD COLUMN name text;

UPDATE sm_control.organization      SET name = organization_id::text;
UPDATE sm_control.organization_unit SET name = organization_unit_id::text;
UPDATE sm_control.environment       SET name = environment_id::text;

ALTER TABLE sm_control.organization      ALTER COLUMN name SET NOT NULL;
ALTER TABLE sm_control.organization_unit ALTER COLUMN name SET NOT NULL;
ALTER TABLE sm_control.environment       ALTER COLUMN name SET NOT NULL;

-- 1-100 characters, no control characters (Unicode Cc: U+0000-U+001F, U+007F-U+009F), not all whitespace.
ALTER TABLE sm_control.organization ADD CONSTRAINT organization_name_shape
    CHECK (char_length(name) BETWEEN 1 AND 100 AND name !~ '[\u0001-\u001f\u007f-\u009f]' AND btrim(name) <> '');
ALTER TABLE sm_control.organization_unit ADD CONSTRAINT organization_unit_name_shape
    CHECK (char_length(name) BETWEEN 1 AND 100 AND name !~ '[\u0001-\u001f\u007f-\u009f]' AND btrim(name) <> '');
ALTER TABLE sm_control.environment ADD CONSTRAINT environment_name_shape
    CHECK (char_length(name) BETWEEN 1 AND 100 AND name !~ '[\u0001-\u001f\u007f-\u009f]' AND btrim(name) <> '');

-- OR-123: display names are unique among siblings, ignoring case. Organizations are unique by name across the
-- install; a sub-organization within its organization; an environment within its sub-organization. The back-filled
-- names are the (unique) ids as text, so creating these indexes over existing rows cannot fail.
CREATE UNIQUE INDEX organization_name_ci_uq ON sm_control.organization (lower(name));
CREATE UNIQUE INDEX organization_unit_name_ci_uq ON sm_control.organization_unit (organization_id, lower(name));
CREATE UNIQUE INDEX environment_name_ci_uq ON sm_control.environment (organization_id, organization_unit_id, lower(name));
