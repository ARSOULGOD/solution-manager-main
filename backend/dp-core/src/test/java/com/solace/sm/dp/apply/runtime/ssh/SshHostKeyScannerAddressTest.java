package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.StrictTargetAddressPolicy;
import com.solace.sm.dp.inventory.StubTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.2: the host-key scan port and the address rule (D-REG sections 7.1 and 7.3), and MF-R4.
 *
 * <p><b>MF-R4 (sshj 0.40.0).</b> {@code SocketClient.connect(InetAddress, int)} connects to the {@code InetAddress} it
 * is given and initialises the transport with {@code getRemoteAddress().getHostName()}; for an address built with
 * {@code InetAddress.getByAddress(name, bytes)} that is the NAME, with no reverse lookup. So a connector can check a
 * name, connect to the very address it checked, and the {@code HostKeyVerifier} still receives the registered name.
 * The first test below proves both halves on the wire without an SSH server: the listener sees a connection from the
 * checked address although the name resolves nowhere, and the client's remote host name is the registered one.
 */
class SshHostKeyScannerAddressTest {

    private ServerSocket listener;
    private final AtomicInteger connections = new AtomicInteger();
    private Thread acceptor;

    @BeforeEach
    void listen() throws IOException {
        listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(2_000);
        acceptor = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    try (Socket accepted = listener.accept()) {
                        connections.incrementAndGet();
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // nothing more within the window
            } catch (IOException ignored) {
                // closed by the test
            }
        }, "scan-address-listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws Exception {
        listener.close();
        acceptor.join(3_000);
    }

    private int connectionsAfterTheWindow() throws InterruptedException {
        acceptor.join(3_000);
        return connections.get();
    }

    @Test
    void mfR4SshjConnectsToTheCheckedAddressWhileTheTransportKeepsTheRegisteredName() throws Exception {
        // a name that resolves nowhere, bound to the address that was checked
        InetAddress checked = InetAddress.getByAddress("registered-name.invalid", new byte[] {127, 0, 0, 1});
        SSHClient ssh = new SSHClient();
        ssh.setConnectTimeout(2_000);
        ssh.setTimeout(2_000);

        assertThrows(IOException.class, () -> ssh.connect(checked, listener.getLocalPort()),
                "the listener hangs up before any SSH banner, so the connect fails after the TCP connection");

        assertEquals("registered-name.invalid", ssh.getRemoteHostname(),
                "the name the HostKeyVerifier is given is the registered one, not a reverse lookup of the address");
        assertEquals(1, connectionsAfterTheWindow(),
                "the connection went to the checked address although the name does not resolve");
        ssh.close();
    }

    @Test
    void theScanServiceRefusesALoopbackNameAndConnectsNowhere() throws Exception {
        HostKeyScanService service = new SshHostKeyScanService(
                new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted()), new SshHostKeyScanner());

        assertThrows(TargetAddressRefusedException.class, () -> service.scan("localhost", listener.getLocalPort()));
        assertThrows(TargetAddressRefusedException.class, () -> service.scan("127.0.0.1", listener.getLocalPort()));
        assertThrows(TargetAddressRefusedException.class, () -> service.scan("169.254.169.254", 22));
        assertEquals(0, connectionsAfterTheWindow());
    }

    @Test
    void theScanServiceConnectsToTheCheckedAddressNotToTheNameAgain() throws Exception {
        // A policy that checks a name that resolves nowhere and hands back the loopback address it "checked". Were the
        // service to connect by name again, the connection would fail to resolve and the listener would see nothing.
        TargetAddressPolicy checkedElsewhere = new StubTargetAddressPolicy(host -> List.of(
                InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})));
        HostKeyScanService service = new SshHostKeyScanService(checkedElsewhere, new SshHostKeyScanner());

        assertThrows(HostKeyScanUnavailableException.class,
                () -> service.scan("unresolvable.invalid", listener.getLocalPort()),
                "the listener presents no SSH key, so the scan finds nothing");

        assertTrue(connectionsAfterTheWindow() >= 1,
                "the scan reached the listener by the checked address, with a name that resolves nowhere");
    }

    @Test
    void aHostThatPresentsNoKeyIsUnavailableAndTheMessageNamesNoHost() {
        HostKeyScanService service = new SshHostKeyScanService(new PermissiveTargetAddressPolicy(),
                new SshHostKeyScanner());

        HostKeyScanUnavailableException e = assertThrows(HostKeyScanUnavailableException.class,
                () -> service.scan("127.0.0.1", 1));

        assertFalse(e.getMessage().contains("127.0.0.1"), e.getMessage());
    }

    @Test
    void aNameThatDoesNotResolveIsUnavailable() {
        TargetAddressPolicy unresolvable = new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted(),
                host -> {
                    throw new java.net.UnknownHostException(host);
                });

        assertThrows(HostKeyScanUnavailableException.class,
                () -> new SshHostKeyScanService(unresolvable, new SshHostKeyScanner()).scan("gone.example", 22));
    }

    @Test
    void aPortOutsideTheRangeAndAMissingHostAreRefusedBeforeAnythingIsResolved() {
        TargetAddressPolicy mustNotBeAsked = new StubTargetAddressPolicy(host -> {
            throw new AssertionError("nothing may be resolved for a malformed request");
        });
        HostKeyScanService service = new SshHostKeyScanService(mustNotBeAsked, new SshHostKeyScanner());

        assertThrows(IllegalArgumentException.class, () -> service.scan("host.example", 0));
        assertThrows(IllegalArgumentException.class, () -> service.scan("host.example", 65536));
        assertThrows(IllegalArgumentException.class, () -> service.scan("", 22));
        assertThrows(IllegalArgumentException.class, () -> service.scan(null, 22));
    }

    @Test
    void theAlgorithmRecordedForAKeyIsItsKeyTypeNotTheSignatureAlgorithmTheScanHappenedToUse() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        PublicKey rsaKey = rsa.generateKeyPair().getPublic();
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        PublicKey ecKey = ec.generateKeyPair().getPublic();

        assertEquals("ssh-rsa", SshHostKeyScanner.algorithmOf(rsaKey));
        assertEquals("ecdsa-sha2-nistp256", SshHostKeyScanner.algorithmOf(ecKey));
    }

    @Test
    void aHostThatCannotBeConnectedToIsGivenUpOnAfterTheFirstAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        List<?> keys = SshHostKeyScanner.scanKeys(ssh -> {
            attempts.incrementAndGet();
            throw new java.net.SocketTimeoutException("connect timed out");
        });

        assertTrue(keys.isEmpty());
        assertEquals(1, attempts.get(), "a blackholed host must cost one connect timeout, not seven");
    }

    @Test
    void aHostThatRefusesAnAlgorithmIsStillTriedWithTheRest() {
        AtomicInteger attempts = new AtomicInteger();

        SshHostKeyScanner.scanKeys(ssh -> {
            attempts.incrementAndGet();
            throw new net.schmizz.sshj.transport.TransportException("no matching host key algorithm");
        });

        assertEquals(7, attempts.get());
    }

    @Test
    void aHostAnIpv6LiteralOrAnythingOutsideTheRegistrationGrammarIsRefusedAtThePort() {
        HostKeyScanService service = new SshHostKeyScanService(new PermissiveTargetAddressPolicy(), new SshHostKeyScanner());

        for (String host : new String[] {"2001:db8::1", "[::1]", "a b", "-x", "host_name", ""}) {
            assertThrows(IllegalArgumentException.class, () -> service.scan(host, 22), host);
        }
    }
}
