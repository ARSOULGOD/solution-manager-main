package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.secret.SecretMaterial;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.UUID;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * X1 (plan 014 Q-V13 (b), BE-D61) against a REAL OpenSSH {@code sshd} ({@link SshdFixture}, {@code OR-124}): the files the
 * Docker transport writes are mode 0640 although the server's umask would create them 0644, and a temp that is still
 * "other"-readable after the {@code setattr} is deleted and refused, the target untouched.
 */
@Testcontainers(disabledWithoutDocker = true)
class SshjDockerTransportFileModeIT {

    private static final String ROOT = "/srv/x1";
    private static final String DIRECTORY = ROOT + "/cache";
    private static final KeyPair CLIENT = SshTestKeys.ed25519Pair();
    private static SshdFixture sshd;

    @BeforeAll
    static void start() {
        sshd = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT));
        sshd.exec("sh", "-c", "mkdir -p " + DIRECTORY + " && chown -R 1000:1000 " + ROOT);
    }

    @AfterAll
    static void stop() {
        if (sshd != null) {
            sshd.close();
        }
    }

    private static String mode(String path) {
        return sshd.exec("stat", "-c", "%a", path);
    }

    private static SSHClient client() throws Exception {
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(sshd.hostKeyFingerprints().get("ed25519"));
        ssh.connect(sshd.host(), sshd.port());
        OpenSSHKeyV1KeyFile keyFile = new OpenSSHKeyV1KeyFile();
        keyFile.init(SshTestKeys.ed25519OpenSsh(CLIENT).text(), null);
        ssh.authPublickey(SshdFixture.USER, keyFile);
        return ssh;
    }

    @Test
    void theTransportWritesTheConfigAPreImageAndAFingerprintAtZeroSixFortyAndReplacesAnExistingWorldReadableFile()
            throws Exception {
        String pin = TargetConnectionTestIT.negotiatedPin(sshd.host(), sshd.port());
        SshCredentialSource credentials = (tenant, ref) -> SecretMaterial.adopt(
                SshTestKeys.ed25519OpenSsh(CLIENT).text().getBytes(StandardCharsets.UTF_8));
        SshjDockerTransport transport = new SshjDockerTransport(credentials, new PermissiveTargetAddressPolicy());
        SshDockerPlacement p = new SshDockerPlacement(new TenantId(UUID.randomUUID()), sshd.host(), sshd.port(),
                SshdFixture.USER, UUID.randomUUID(), pin, "cache", ROOT, DIRECTORY, "/app/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
        String config = DIRECTORY + "/application.yml";
        String preimage = DIRECTORY + "/.application.yml.sm-preimage-1";
        String fingerprint = DIRECTORY + "/.application.yml.sm-fp-1";
        // The state measured live before X1: an existing file written by an SSH session with umask 0002 (664).
        sshd.exec("sh", "-c", "echo old > " + config + " && chown 1000:1000 " + config + " && chmod 664 " + config);
        assertEquals("664", mode(config));

        transport.writeFileAtomically(p, config, "a: 1\n".getBytes(StandardCharsets.UTF_8));
        transport.writeFileAtomically(p, preimage, "old\n".getBytes(StandardCharsets.UTF_8));
        transport.writeFileAtomically(p, fingerprint, "fp".getBytes(StandardCharsets.UTF_8));

        assertEquals("640", mode(config), "the replaced config");
        assertEquals("640", mode(preimage));
        assertEquals("640", mode(fingerprint));
        assertEquals("a: 1", sshd.exec("cat", config));
        String sshUser = sshd.exec("id", "-u", SshdFixture.USER) + ":" + sshd.exec("id", "-g", SshdFixture.USER);
        assertEquals(sshUser, sshd.exec("stat", "-c", "%u:%g", config), "the replaced config is owned by the SSH user");
        assertEquals("", sshd.exec("sh", "-c", "ls -A " + DIRECTORY + " | grep '\\.tmp$' || true"), "no temp left behind");
    }

    /** A real-wire SFTP whose setattr is dropped; {@code ignoreCreateMode} also drops the create's attributes (an old server). */
    private static SshjDockerTransport.RemoteFiles ignoringSetattr(SFTPClient sftp, boolean ignoreCreateMode) {
        SshjDockerTransport.RemoteFiles real = SshjDockerTransport.filesOver(sftp);
        return new SshjDockerTransport.RemoteFiles() {
            @Override public void create(String path, byte[] content, int mode) throws IOException {
                if (ignoreCreateMode) {
                    try (net.schmizz.sshj.sftp.RemoteFile f = sftp.open(path, java.util.EnumSet.of(
                            net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.EXCL,
                            net.schmizz.sshj.sftp.OpenMode.WRITE))) {
                        f.write(0, content, 0, content.length);
                    }
                } else {
                    real.create(path, content, mode);
                }
            }
            @Override public void setMode(String path, int mode) { }
            @Override public int mode(String path) throws IOException { return real.mode(path); }
            @Override public void rename(String from, String to) throws IOException { real.rename(from, to); }
            @Override public void remove(String path) throws IOException { real.remove(path); }
        };
    }

    @Test
    void theTempIsBornZeroSixFortyBeforeAnySetattrAndTheWriteSucceedsWhenTheSetattrIsDropped() throws Exception {
        String target = DIRECTORY + "/born.yml";
        try (SSHClient ssh = client(); SFTPClient sftp = ssh.newSFTPClient()) {
            SshjDockerTransport.RemoteFiles dropped = ignoringSetattr(sftp, false);
            SshjDockerTransport.RemoteFiles observing = new SshjDockerTransport.RemoteFiles() {
                @Override public void create(String path, byte[] content, int mode) throws IOException {
                    dropped.create(path, content, mode);
                    // X1-1: no setattr has run (it is dropped); the mode is the create's own.
                    assertEquals("640", Integer.toOctalString(mode(path)), "the temp is born 0640");
                }
                @Override public void setMode(String path, int mode) throws IOException { dropped.setMode(path, mode); }
                @Override public int mode(String path) throws IOException { return dropped.mode(path); }
                @Override public void rename(String from, String to) throws IOException { dropped.rename(from, to); }
                @Override public void remove(String path) throws IOException { dropped.remove(path); }
            };
            SshjDockerTransport.writeAtomically(observing, target + ".sm-it.tmp", target, "s\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("640", mode(target));
    }

    @Test
    void aTempStillReadableByOthersAfterTheSetattrIsDeletedAndRefusedAndTheTargetIsUntouched() throws Exception {
        String target = DIRECTORY + "/refused.yml";
        sshd.exec("sh", "-c", "echo keep > " + target + " && chown 1000:1000 " + target + " && chmod 640 " + target);
        try (SSHClient ssh = client(); SFTPClient sftp = ssh.newSFTPClient()) {
            // A server that honours neither the create's attributes nor the setattr: the temp keeps the umask-derived mode.
            SshjDockerTransport.RemoteFiles old = ignoringSetattr(sftp, true);
            String temp = target + ".sm-it.tmp";

            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> SshjDockerTransport.writeAtomically(old, temp, target, "leak\n".getBytes(StandardCharsets.UTF_8)));

            assertEquals(SshjDockerTransport.MODE_REFUSED, e.getMessage());
            assertEquals("ssh.config.mode.too.open", e.getMessage());
        }
        assertEquals("keep", sshd.exec("cat", target), "the target was never replaced");
        assertEquals("640", mode(target));
        assertTrue(sshd.exec("sh", "-c", "ls -A " + DIRECTORY + " | grep -c 'refused.yml.sm-it.tmp' || true").equals("0"),
                "the refused temp was deleted");
    }
}
