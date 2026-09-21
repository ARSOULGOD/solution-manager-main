-- Terminal states stay terminal by TRIGGER — with exactly ONE legal terminal transition:
-- STAGED → SUPERSEDED, because §3.3's admission algorithm requires marking the elder STAGED row
-- before inserting its replacement (and staged_fence enforces that order). Everything else that is
-- terminal is immutable. Owner-owned like everything else.
CREATE FUNCTION sm_data.forbid_terminal_regression() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'STAGED' AND NEW.state = 'SUPERSEDED' THEN
        RETURN NEW;                            -- sanctioned transition 1: the supersession mark
                                               -- (which also refreshes terminal_at to the
                                               -- supersession time — §3.2)
    END IF;
    IF OLD.state NOT IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE') THEN
        IF NEW.state = OLD.state
           AND to_jsonb(NEW) - 'attempt_summary' = to_jsonb(OLD) - 'attempt_summary' THEN
            RETURN NEW;                        -- sanctioned write 2: §3.3's compaction fold —
                                               -- attempt_summary, and ONLY attempt_summary, may be
                                               -- written on a terminal row (measured: without this
                                               -- branch, compaction was unimplementable while the
                                               -- attempt DELETE half succeeded)
        END IF;
        RAISE EXCEPTION 'apply_request % is terminal (%) and immutable', OLD.apply_id, OLD.state;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_request_terminal_guard BEFORE UPDATE ON sm_data.apply_request
    FOR EACH ROW EXECUTE FUNCTION sm_data.forbid_terminal_regression();
