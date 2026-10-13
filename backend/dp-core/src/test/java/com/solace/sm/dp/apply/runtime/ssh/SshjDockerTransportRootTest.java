package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.schmizz.sshj.sftp.FileMode;
import org.junit.jupiter.api.Test;

/**
 * T6.1 / B2, third enforcer (M8, measured: {@code SFTPClient.canonicalize} sends SSH_FXP_REALPATH and a live
 * OpenSSH sftp-server resolves a symbolic link; {@code lstat} reports the link itself).
 */
class SshjDockerTransportRootTest {

    private static SshDockerPlacement placement(String root, String directory) {
        return new SshDockerPlacement(new TenantId(UUID.randomUUID()), "host.example", 22, "demo",
                UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", root, directory, "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    /** A fake SFTP: every canonicalization answers {@code canonical}; the type is {@code type}. */
    private static final class FakeSftp implements SshjDockerTransport.DirectoryView {
        final List<String> calls = new ArrayList<>();
        private final String canonical;
        private final FileMode.Type type;

        FakeSftp(String canonical, FileMode.Type type) {
            this.canonical = canonical;
            this.type = type;
        }

        @Override
        public FileMode.Type typeOf(String path) throws IOException {
            calls.add("lstat " + path);
            return type;
        }

        @Override
        public String canonicalize(String path) throws IOException {
            calls.add("realpath " + path);
            return canonical;
        }
    }

    private static String refusal(FakeSftp sftp, SshDockerPlacement p) {
        return assertThrows(RuntimeAdapterException.class, () -> SshjDockerTransport.verifyDirectory(sftp, p))
                .errorClass().token();
    }

    @Test
    void aCanonicalDirectoryOutsideTheRootIsRefusedBeforeAnyWrite() {
        // /srv/sm/cache is a symbolic link the operator (or an attacker) pointed at the SSH user's own keys
        FakeSftp sftp = new FakeSftp("/home/u/.ssh", FileMode.Type.DIRECTORY);

        assertEquals("ssh.config.outside.root", refusal(sftp, placement("/srv/sm", "/srv/sm/cache")));
        assertEquals(List.of("realpath /srv/sm/cache"), sftp.calls,
                "refused on the canonical path alone: nothing else was asked, nothing written");
    }

    @Test
    void aSiblingWhoseNameMerelyStartsWithTheRootIsOutsideIt() {
        assertEquals("ssh.config.outside.root",
                refusal(new FakeSftp("/srv/smx/cache", FileMode.Type.DIRECTORY), placement("/srv/sm", "/srv/sm/cache")));
    }

    @Test
    void aCanonicalDirectoryEqualToTheDeclaredOneInsideTheRootPasses() {
        assertDoesNotThrow(() -> SshjDockerTransport.verifyDirectory(
                new FakeSftp("/srv/sm/cache", FileMode.Type.DIRECTORY), placement("/srv/sm", "/srv/sm/cache")));
        assertDoesNotThrow(() -> SshjDockerTransport.verifyDirectory(
                new FakeSftp("/srv/sm", FileMode.Type.DIRECTORY), placement("/srv/sm", "/srv/sm")));
    }

    @Test
    void aLinkInsideTheRootThatLeadsToAnotherDirectoryInsideItIsStillRefused() {
        // inside the root, but not the directory the placement declared: the declared path must be canonical
        assertEquals("ssh.config.directory.invalid",
                refusal(new FakeSftp("/srv/sm/other", FileMode.Type.DIRECTORY), placement("/srv/sm", "/srv/sm/cache")));
    }

    @Test
    void aDeclaredDirectoryThatIsNotADirectoryIsRefused() {
        assertEquals("ssh.config.directory.invalid",
                refusal(new FakeSftp("/srv/sm/cache", FileMode.Type.SYMLINK), placement("/srv/sm", "/srv/sm/cache")));
    }
}
