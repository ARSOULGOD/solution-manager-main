package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.4, the blackholed-scan concern W1 left open ("up to seven connections, five seconds each"): a scan now
 * runs ON THE REQUEST PATH ({@code scanHostKeys}, and the pre-check of {@code registerTarget}), so a host that accepts
 * the TCP connection and then says nothing must not hold a servlet thread for seven read timeouts.
 *
 * <p>The scanner already stops at once when the connection itself cannot be made (D-5). What it did not do is stop when
 * the connection is made and the peer is SILENT: the banner read times out, which is an ordinary {@code IOException}
 * to the loop's "this algorithm was refused, try the next" arm, so each of the seven algorithms waited its own timeout
 * (about 35 s). A peer that does not answer the first attempt will not answer the others, so a timeout of any kind ends
 * the scan, and a total budget bounds even a peer that answers slowly.
 */
class SshHostKeyScannerSilentHostTest {

    private ServerSocket listener;
    private Thread acceptor;
    private final AtomicInteger accepted = new AtomicInteger();
    private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void listenAndSayNothing() throws IOException {
        listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    held.add(listener.accept());      // accept, keep the socket open, never write a banner
                    accepted.incrementAndGet();
                }
            } catch (IOException ignored) {
                // closed by the test
            }
        }, "silent-ssh-listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws Exception {
        listener.close();
        for (Socket socket : held) {
            socket.close();
        }
        acceptor.join(2_000);
    }

    @Test
    void aPeerThatAcceptsAndIsSilentEndsTheScanAfterOneTimeoutNotSeven() {
        long started = System.nanoTime();

        List<OfferedHostKey> found = SshHostKeyScanner.scanKeys(
                ssh -> ssh.connect(InetAddress.getLoopbackAddress(), listener.getLocalPort()));

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(found.isEmpty(), "a silent peer offers no key");
        assertEquals(1, accepted.get(), "one attempt only: a peer silent to the first is silent to the rest");
        assertTrue(took.compareTo(Duration.ofSeconds(9)) < 0,
                "one read timeout (5 s) plus slack, never seven of them; took " + took);
    }

    @Test
    void theBudgetBoundsTheWholeScanWhateverEachAttemptDoes() {
        long started = System.nanoTime();

        // A budget shorter than one attempt's own timeout: the attempt's timeout is the smaller of the two.
        List<OfferedHostKey> found = SshHostKeyScanner.scanKeys(
                ssh -> ssh.connect(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                Duration.ofSeconds(2));

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(found.isEmpty());
        assertTrue(took.compareTo(Duration.ofSeconds(4)) < 0, "the 2 s budget caps the attempt; took " + took);
    }

    /**
     * Review W3C-3: sshj's timeouts are per READ. A peer that sends one identification-header byte inside every read
     * timeout (never an {@code SSH-} line) resets the read timer forever, so without a hard deadline the first attempt
     * never ends. The dribbler here sends a byte every 300 ms against a 1 s budget; the scan must return near the budget.
     */
    @Test
    void aPeerThatDribblesBannerBytesCannotHoldTheScanPastItsBudget() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread dribbler = new Thread(() -> {
            try (Socket peer = listener.accept()) {
                java.io.OutputStream out = peer.getOutputStream();
                while (!stop.get()) {
                    out.write('x');                   // never a newline, never "SSH-": the parser keeps reading
                    out.flush();
                    Thread.sleep(300);
                }
            } catch (IOException | InterruptedException ignored) {
                // the scan closed its end, or the test is over
            }
        }, "dribbling-ssh-listener");
        dribbler.setDaemon(true);
        // the fixture's acceptor would take the connection first: stop it so the dribbler is the only acceptor
        listener.close();
        listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        int port = listener.getLocalPort();
        dribbler.start();
        try {
            long started = System.nanoTime();

            List<OfferedHostKey> found = SshHostKeyScanner.scanKeys(
                    ssh -> ssh.connect(InetAddress.getLoopbackAddress(), port), Duration.ofSeconds(1));

            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertTrue(found.isEmpty());
            assertTrue(took.compareTo(Duration.ofMillis(2_500)) < 0,
                    "the 1 s budget is a deadline, not a per-read timeout; took " + took);
        } finally {
            stop.set(true);
            dribbler.join(2_000);
        }
    }
}
