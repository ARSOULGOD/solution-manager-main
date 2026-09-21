package com.solace.sm.events.contracttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Mutates the JVM-global `sm.contracts.root`, which other suites read in static initializers —
// correct only while nothing else runs alongside it.
@org.junit.jupiter.api.parallel.Isolated
class ContractArtifactsTest {

    @TempDir Path tmp;

    @Test
    void findsEventsFixturesFromTheRepository() {
        Path fixtures = ContractArtifacts.dir("events/fixtures");
        assertTrue(Files.isDirectory(fixtures), fixtures.toString());
        assertFalse(ContractArtifacts.jsonFiles(fixtures, "expected-topics.json").isEmpty());
    }

    @Test
    void overridePointsAtTheContractsRoot() throws Exception {
        Path events = Files.createDirectories(tmp.resolve("events/fixtures"));
        Files.writeString(events.resolve("one.json"), "{}\n");
        String old = System.getProperty("sm.contracts.root");
        try {
            System.setProperty("sm.contracts.root", tmp.toString());
            assertEquals(events, ContractArtifacts.dir("events/fixtures"));
        } finally {
            restore(old);
        }
    }

    @Test
    void overrideFailsLoudlyWhenTheSubtreeIsMissing() {
        String old = System.getProperty("sm.contracts.root");
        try {
            System.setProperty("sm.contracts.root", tmp.toString());
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ContractArtifacts.dir("events/fixtures"));
            assertTrue(e.getMessage().contains("events/fixtures"), e.getMessage());
        } finally {
            restore(old);
        }
    }

    @Test
    void aBlankOverrideIsAMisconfigurationNotASilentFallback() {
        String old = System.getProperty("sm.contracts.root");
        try {
            System.setProperty("sm.contracts.root", "");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ContractArtifacts.dir("events/fixtures"));
            assertTrue(e.getMessage().contains("sm.contracts.root is set but blank"),
                    e.getMessage());
        } finally {
            restore(old);
        }
    }

    @Test
    void anEmptyDirectoryIsARedResultNotAnEmptyList() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("events/empty"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ContractArtifacts.jsonFiles(empty));
        assertTrue(e.getMessage().contains("no JSON files"), e.getMessage());
    }

    private static void restore(String old) {
        if (old == null) {
            System.clearProperty("sm.contracts.root");
        } else {
            System.setProperty("sm.contracts.root", old);
        }
    }
}
