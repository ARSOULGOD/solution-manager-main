package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.RequestState;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Closes the one Java↔DDL gap the plan 004 reviews left open: nothing previously tied
 * {@code state_fits_operation}'s ACTUAL TEXT — the migration bytes Flyway executes — to
 * {@link RequestState#validFor}.
 *
 * <p>Before this test, two locks existed and neither covered the pairing:
 * {@code SketchMirrorTest} proves the migration file's statement text equals the design document's —
 * a text-to-text lock, blind to what either side MEANS — and {@code RequestStateTest} proves
 * {@code validFor} against a HAND-TRANSCRIBED copy of the CHECK's three branches, written directly
 * into that test's source rather than read from the shipped SQL. An edit to the CHECK inside
 * {@code V002__apply_request.sql} — with the design document edited to match, so
 * {@code SketchMirrorTest} stays green — that forgot to update {@code validFor} (or vice versa) would
 * leave every existing test passing.
 *
 * <p>This test reads the CHECK's three branches out of the SHIPPED migration file with a regex, builds
 * the three sets the CASE expression names, and compares them directly against
 * {@link RequestState#validFor} for all 36 {@code (state, operation)} pairs — no hand transcription on
 * either side. A change to the CHECK's state lists that is not mirrored in {@code validFor} (or the
 * reverse) fails here, naming the pair that disagrees.
 *
 * <p>Surefire, not failsafe: this is a text-and-enum comparison, no database required.
 */
class StateFitsOperationMirrorTest {

    /**
     * Anchors on the constraint's own structure rather than the whole file, so the pattern survives
     * reformatting elsewhere in the migration and fails loudly (empty groups) if the CHECK's shape
     * itself changes in a way this regex no longer recognises.
     */
    private static final Pattern STATE_FITS_OPERATION = Pattern.compile(
            "CONSTRAINT\\s+state_fits_operation\\s+CHECK\\s*\\(\\s*CASE\\s+operation\\s+"
                    + "WHEN\\s+'stop'\\s+THEN\\s+state\\s+IN\\s*\\(([^)]*)\\)\\s+"
                    + "WHEN\\s+'restart'\\s+THEN\\s+state\\s+NOT\\s+IN\\s*\\(([^)]*)\\)\\s+"
                    + "ELSE\\s+state\\s+NOT\\s+IN\\s*\\(([^)]*)\\)\\s+END\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void everyStateAndOperationPairAgreesWithTheShippedMigrationText() {
        Extracted fromDdl = extract(migrationText());

        for (RequestState state : RequestState.values()) {
            assertEquals(fromDdl.stop.contains(state), state.validFor(Operation.STOP),
                    "stop / " + state + " — validFor disagrees with the SHIPPED state_fits_operation "
                            + "CHECK (V002__apply_request.sql); one of them changed without the other");
            assertEquals(!fromDdl.restartDenied.contains(state), state.validFor(Operation.RESTART),
                    "restart / " + state + " — validFor disagrees with the SHIPPED CHECK");
            assertEquals(!fromDdl.applyDenied.contains(state), state.validFor(Operation.APPLY),
                    "apply / " + state + " — validFor disagrees with the SHIPPED CHECK");
        }
    }

    @Test
    void theExtractionItselfIsNonVacuous() {
        Extracted fromDdl = extract(migrationText());
        assertFalse(fromDdl.stop.isEmpty(), "the regex found no 'stop' branch — it stopped matching "
                + "the shipped CHECK text, which proves nothing rather than proving agreement");
        assertFalse(fromDdl.restartDenied.isEmpty(), "the regex found no 'restart' branch");
        assertFalse(fromDdl.applyDenied.isEmpty(), "the regex found no ELSE (apply) branch");
        assertEquals(12, RequestState.values().length,
                "design §3.2 has twelve states; a thirteenth needs both a migration and validFor edit");
    }

    /**
     * Prove-it-fires: the same pattern applied against a one-token mutation of the shipped text — the
     * class of edit {@code SketchMirrorTest} pins on the OTHER side of this mirror (migration vs
     * design) — must disagree with {@code validFor} for the state the mutation moved.
     */
    @Test
    void aOneStateMutationOfTheShippedCheckIsDetected() {
        String original = migrationText();
        // Move STOP_FAILED out of the 'stop' branch's allow-list — still syntactically valid SQL, and
        // exactly the shape of edit that would silently make the DB accept `stop` requests it should
        // refuse if validFor were not also changed to match.
        //
        // This literal is a hand-transcribed COPY of the shipped 'stop' branch, on purpose (a mutation
        // has to start from real text to prove anything) — so a legitimate, coordinated edit to that
        // branch (migration + design + validFor, kept mutually consistent) will fail the assertion
        // below with "the mutation target text must exist in the shipped file". That message is a
        // maintenance signal, not a correctness failure: re-point this literal (and the assertions
        // below it) to the branch's new text rather than treating the failure as a regression.
        String mutated = original.replace(
                "state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')",
                "state IN ('REQUESTED','RUNNING','STOPPED')");
        assertNotEquals(original, mutated, "the mutation target text must exist in the shipped file — "
                + "if the 'stop' branch legitimately changed, re-point this literal to its new text "
                + "rather than treating this failure as a regression");

        Extracted fromMutatedDdl = extract(mutated);
        assertFalse(fromMutatedDdl.stop.contains(RequestState.STOP_FAILED),
                "the mutation must remove STOP_FAILED from the parsed 'stop' allow-list");

        // validFor (unmutated) still says STOP_FAILED is valid for stop — so a mirror test run against
        // the mutated text must find a disagreement, exactly as it would if this test were live during
        // a real, non-matching edit.
        assertTrue(RequestState.STOP_FAILED.validFor(Operation.STOP),
                "validFor is unchanged by this in-memory mutation of the DDL text");
        assertNotEquals(fromMutatedDdl.stop.contains(RequestState.STOP_FAILED),
                RequestState.STOP_FAILED.validFor(Operation.STOP),
                "a mutated CHECK and an unmutated validFor must disagree on STOP_FAILED/stop — if they "
                        + "agree, this mirror cannot detect the class of drift it exists to catch");
    }

    // -------------------------------------------------------------------------------------------

    private record Extracted(Set<RequestState> stop, Set<RequestState> restartDenied,
            Set<RequestState> applyDenied) {
    }

    private static Extracted extract(String sql) {
        Matcher m = STATE_FITS_OPERATION.matcher(sql);
        if (!m.find()) {
            throw new IllegalStateException("state_fits_operation not found in the shipped migration "
                    + "text with the expected CASE shape — the constraint moved or was reworded; "
                    + "re-point this test's pattern rather than deleting it");
        }
        return new Extracted(states(m.group(1)), states(m.group(2)), states(m.group(3)));
    }

    private static Set<RequestState> states(String commaSeparatedQuoted) {
        Set<RequestState> out = EnumSet.noneOf(RequestState.class);
        for (String token : commaSeparatedQuoted.split(",")) {
            String name = token.trim().replace("'", "");
            if (!name.isEmpty()) {
                out.add(RequestState.valueOf(name));
            }
        }
        return out;
    }

    /** The actual bytes Flyway executes for {@code V002}, not the design document's copy. */
    private static String migrationText() {
        Path file = moduleDir().resolve(
                "src/main/resources/db/migration/sm_data/V002__apply_request.sql");
        return DesignSketch.read(file);
    }

    private static Path moduleDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path relative = Path.of("src/main/resources/db/migration/sm_data");
        return java.nio.file.Files.isDirectory(cwd.resolve(relative))
                ? cwd
                : DesignSketch.repoRoot().resolve("backend/dp-core");
    }
}
