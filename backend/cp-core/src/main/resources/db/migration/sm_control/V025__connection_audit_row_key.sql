-- Complete the connection audit correction for databases whose existing audit
-- function used formatting different from V024's literal replacement text.
-- The operation vocabulary is already corrected by V024; this migration only
-- adds the connection row key and is safe to run more than once.

DO $migration$
DECLARE
    definition text;
    patched text;
BEGIN
    SELECT pg_get_functiondef(p.oid)
      INTO definition
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'sm_control'
       AND p.proname = 'record_change_audit'
       AND pg_get_function_identity_arguments(p.oid) = '';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'sm_control.record_change_audit() is missing';
    END IF;

    IF position('WHEN ''connection'' THEN' IN definition) = 0 THEN
        patched := regexp_replace(
            definition,
            $$WHEN 'target' THEN[[:space:]]+jsonb_build_object\('target_id',[[:space:]]+COALESCE\(v_new_json ->> 'target_id',[[:space:]]+v_old_json ->> 'target_id'\)\)[[:space:]]+WHEN 'dashboard' THEN$$,
            $$WHEN 'target' THEN
            jsonb_build_object('target_id', COALESCE(v_new_json ->> 'target_id', v_old_json ->> 'target_id'))
        WHEN 'connection' THEN
            jsonb_build_object('connection_id', COALESCE(v_new_json ->> 'connection_id', v_old_json ->> 'connection_id'))
        WHEN 'dashboard' THEN$$,
            'g');

        IF patched = definition OR position('WHEN ''connection'' THEN' IN patched) = 0 THEN
            RAISE EXCEPTION 'could not patch sm_control.record_change_audit() with the connection row key';
        END IF;

        EXECUTE patched;
    END IF;
END
$migration$;
