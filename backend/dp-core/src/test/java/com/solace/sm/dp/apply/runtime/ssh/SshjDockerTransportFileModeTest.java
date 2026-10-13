package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * X1 (plan 014 Q-V13 (b), BE-D61): the Docker transport gives every file it writes the explicit mode 0640 by a
 * {@code setattr} on the temp file, stat-checks it BEFORE the rename (deleting and refusing on an "other" bit), and
 * stat-checks the target after. The SFTP seam is a recording fake; the real wire is {@code SshjDockerTransportFileModeIT}.
 */
class SshjDockerTransportFileModeTest {

    private static final String TARGET = "/srv/sm/cache/application.yml";
    private static final String TEMP = TARGET + ".sm-x.tmp";

    /** An in-memory SFTP: the mode a file is created with is {@code createMode} (the server's umask), setMode changes it. */
    private static final class FakeFiles implements SshjDockerTransport.RemoteFiles {
        final List<String> calls = new ArrayList<>();
        final Map<String, Integer> modes = new HashMap<>();
        int createMode = 0664;
        boolean ignoreSetMode;
        /** The mode the target reports after the rename, when different from the temp's. */
        Integer targetModeAfterRename;

        boolean setModeRejected;
        boolean ignoreCreateMode;
        boolean statAfterRenameFails;
        /** A server that honours the mode in the create request gives the file exactly that; one that ignores it, createMode. */
        @Override public void create(String path, byte[] content, int mode) {
            calls.add("create " + path + " " + Integer.toOctalString(mode));
            modes.put(path, ignoreCreateMode ? createMode : mode);
        }
        @Override public void setMode(String path, int mode) throws IOException {
            if (setModeRejected) { throw new IOException("SETSTAT refused"); }
            calls.add("setMode " + path + " " + Integer.toOctalString(mode));
            if (!ignoreSetMode) { modes.put(path, mode); }
        }
        @Override public int mode(String path) throws IOException {
            calls.add("stat " + path);
            if (statAfterRenameFails && path.equals(TARGET)) { throw new IOException("stat failed"); }
            return modes.get(path);
        }
        @Override public void rename(String from, String to) {
            calls.add("rename " + from + " " + to);
            modes.put(to, targetModeAfterRename != null ? targetModeAfterRename : modes.remove(from));
        }
        @Override public void remove(String path) { calls.add("rm " + path); modes.remove(path); }
    }

    @Test
    void theRequestedModeIsNamedAndHasNoOtherBit() {
        assertEquals(0640, SshjDockerTransport.SECRET_FILE_MODE);
        assertEquals(0, SshjDockerTransport.SECRET_FILE_MODE & 07);
    }

    @Test
    void anExplicitModeIsSetOnTheTempAndCheckedBeforeTheRenameAndTheTargetIsCheckedAfter() throws IOException {
        FakeFiles files = new FakeFiles();                  // the server would have created 0664

        SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[] {1});

        assertEquals(List.of("create " + TEMP + " 640", "setMode " + TEMP + " 640", "stat " + TEMP,
                "rename " + TEMP + " " + TARGET, "stat " + TARGET), files.calls);
        assertEquals(0640, files.modes.get(TARGET));
    }

    @Test
    void aTempThatKeepsAnOtherBitAfterTheSetattrIsDeletedAndTheTargetIsNeverRenamedOnto() {
        FakeFiles files = new FakeFiles();
        files.ignoreSetMode = true;                         // a server that ignores both attributes: the temp stays 0664
        files.ignoreCreateMode = true;

        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[] {1}));

        assertEquals("ssh.config.mode.too.open", e.getMessage());
        assertTrue(files.calls.contains("rm " + TEMP), "the temp is deleted");
        assertFalse(files.calls.stream().anyMatch(c -> c.startsWith("rename")), "never renamed onto the target");
        assertFalse(files.modes.containsKey(TARGET));
    }

    @Test
    void aTargetThatReportsAnOtherBitAfterTheRenameIsRefused() {
        FakeFiles files = new FakeFiles();
        files.targetModeAfterRename = 0644;

        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[] {1}));

        assertEquals("ssh.config.mode.too.open", e.getMessage());
    }

    @Test
    void everyOtherBitIsRefused() {
        for (int other : new int[] {01, 02, 04}) {
            FakeFiles files = new FakeFiles();
            files.ignoreSetMode = true;
            files.ignoreCreateMode = true;
            files.createMode = 0640 | other;
            assertThrows(RuntimeAdapterException.class,
                    () -> SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[0]), "other bit " + other);
        }
    }

    @Test
    void theTempIsAskedForWithTheModeAtCreateSoItIsNeverMoreOpenThanThatEvenBeforeTheSetattr() throws IOException {
        FakeFiles files = new FakeFiles();
        files.createMode = 0666;                            // the server's umask would have produced 0666 & ~umask
        files.setModeRejected = false;

        SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[] {1});

        assertEquals("create " + TEMP + " 640", files.calls.get(0));
    }

    @Test
    void aServerThatRejectsTheSetstatIsClassifiedUnsupportedAndTheTempIsDeleted() {
        FakeFiles files = new FakeFiles();
        files.setModeRejected = true;

        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[] {1}));

        assertEquals("ssh.config.mode.unsupported", e.getMessage());
        assertTrue(files.calls.contains("rm " + TEMP));
        assertFalse(files.calls.stream().anyMatch(c -> c.startsWith("rename")));
    }

    @Test
    void aStatThatFailsAfterTheRenameIsClassifiedUnverifiedAndTheTargetIsAlreadyReplaced() {
        FakeFiles files = new FakeFiles();
        files.statAfterRenameFails = true;

        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.writeAtomically(files, TEMP, TARGET, new byte[] {1}));

        assertEquals("ssh.config.mode.unverified", e.getMessage());
        assertTrue(files.modes.containsKey(TARGET), "the rename happened: the target is the new file");
    }
}
