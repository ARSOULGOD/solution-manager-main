package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan 011 P11-8.1 Step 2b: the operator's CPU command is run without a shell and only its summary is reported. */
class CpuSamplerTest {

    @TempDir
    Path dir;

    @Test
    void aCommandThatPrintsVmstatLinesIsSummarisedAndStopped() throws IOException {
        Path fake = Files.writeString(dir.resolve("fake-vmstat.sh"), """
                echo 'procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----'
                echo ' r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st'
                echo ' 1  0      0 123456  12345 234567    0    0     1     2   10   20  5  3 90  1  1'
                echo ' 2  0      0 123456  12345 234567    0    0     1     2   10   20 40 10 40  5  5'
                sleep 60
                """);
        CpuSampler sampler = CpuSampler.start(List.of("bash", fake.toString()));
        assertTrue(sampler.active());
        assertFalse(sampler.linesSoFar().isEmpty(), "start() returns only once the command has printed");
        Vmstat.Summary stopped = sampler.stop();
        assertEquals(1, stopped.samples(), "the since-boot line is dropped, one sample remains");
        assertEquals(50.0, stopped.meanBusyPercent(), 0.001);
    }

    @Test
    void noCommandIsInertAndAnImpossibleOneIsRefusedNamingOnlyTheClass() {
        CpuSampler none = CpuSampler.start(List.of());
        assertFalse(none.active());
        assertEquals(0, none.stop().samples());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CpuSampler.start(List.of("/no/such/command-secret-host-name")));
        assertFalse(e.getMessage().contains("secret-host-name"), "the command line is not echoed: " + e.getMessage());
    }
}
