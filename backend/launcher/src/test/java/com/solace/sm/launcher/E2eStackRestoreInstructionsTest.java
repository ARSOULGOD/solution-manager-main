package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W4 review W4A-2: every MANUAL recovery instruction the walkthrough restore writes names the directory and file of the
 * placement being restored. The second Cache of the multi-placement gate has its own directory; an instruction naming the
 * first Cache's would send an operator to delete or overwrite a healthy, unrelated config file.
 */
class E2eStackRestoreInstructionsTest {

    private static final String FIRST = "/srv/cache-engine/config";
    private static final String SECOND = "/srv/cache-engine/config/second";
    private static final String FILE = "application.yml";

    @Test
    void everyInstructionNamesTheDirectoryItWasGivenAndOnlyThat() {
        List<String> instructions = List.of(
                E2eStack.manualAbsent(SECOND, FILE),
                E2eStack.manualNoGeneration(SECOND, FILE, "sha-now", "sha-original"),
                E2eStack.manualRefused(SECOND, FILE, "rollback.refused", 7L, "sha-original"),
                E2eStack.manualFailed(SECOND, FILE, "RuntimeException"));

        for (String line : instructions) {
            assertTrue(line.startsWith("MANUAL"), line);
            assertTrue(line.contains(SECOND), "names the placement's own directory: " + line);
            assertFalse(line.replace(SECOND, "").contains(FIRST), "never another placement's directory: " + line);
        }
    }

    @Test
    void theAbsentOriginalInstructionDeletesTheGivenFileAndNoOther() {
        String line = E2eStack.manualAbsent(SECOND, FILE);

        assertTrue(line.contains("Delete " + SECOND + "/" + FILE + " by hand"), line);
        assertFalse(line.contains("Delete " + FIRST + "/" + FILE), line);
    }

    @Test
    void theRefusedInstructionCopiesTheGivenPreImageOverTheGivenFile() {
        String line = E2eStack.manualRefused(SECOND, FILE, "rollback.refused", 7L, "sha-original");

        assertTrue(line.contains("Copy " + SECOND + "/.application.yml.sm-preimage-7 over " + SECOND + "/application.yml"),
                line);
        assertTrue(line.contains("sha-original"), line);
    }
}
