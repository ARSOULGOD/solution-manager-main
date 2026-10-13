package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan 010 T6.6: the evidence a run leaves behind can never carry a secret the run was handed (I6). */
class EvidenceLogTest {

    @TempDir
    Path tmp;

    @Test
    void aLineCarryingAForbiddenSecretIsRefusedWithoutEchoingIt() {
        EvidenceLog log = new EvidenceLog("run");
        log.forbid("s3cr3t-broker-password");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> log.record("Setup", "connected with s3cr3t-broker-password to the broker"));

        assertFalse(refused.getMessage().contains("s3cr3t"), "the refusal does not repeat the secret");
        assertFalse(log.render().contains("s3cr3t"));
    }

    @Test
    void aShortOrBlankForbiddenValueIsIgnoredBecauseItWouldRefuseEverything() {
        EvidenceLog log = new EvidenceLog("run");
        log.forbid("");
        log.forbid("ab");
        log.forbid(null);

        log.record("Setup", "an ordinary line");

        assertTrue(log.render().contains("an ordinary line"));
    }

    @Test
    void sectionsKeepTheirOrderAndSkipsAreListedWithTheirReasons() throws IOException {
        EvidenceLog log = new EvidenceLog("Skeleton walkthrough");
        log.record("B. Second", "two");
        log.record("A. First", "one");
        log.record("B. Second", "three");
        log.skip("live notify for the browser", "open items 32/33/35");

        String text = log.render();

        assertTrue(text.indexOf("B. Second") < text.indexOf("A. First"), "first-recorded section first");
        assertTrue(text.contains("- two\n- three"), text);
        assertTrue(text.contains("SKIPPED: live notify for the browser - open items 32/33/35"), text);
        Path written = log.writeTo(tmp.resolve("nested/dir"), "Skeleton");
        assertEquals(text, Files.readString(written));
        assertTrue(written.getFileName().toString().startsWith("Skeleton-"));
    }
}
