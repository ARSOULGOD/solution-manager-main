package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.dp.apply.runtime.ssh.SshTargetConnectionTester;
import com.solace.sm.dp.secret.SecretResolver;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Plan 013 T1.5: the connection test is a bean, it is the SSH one, and it is built over the STRICT address policy: a
 * target whose host is a loopback address is not connected to (a listener on it sees nothing) and is a result, not an
 * error. The only way to relax the policy is a test's own {@code @Primary} bean ({@code TargetAddressConfigurationTest}).
 */
class TargetConnectionTestConfigurationTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final String PIN = "SHA256:" + "P".repeat(43);

    @Configuration(proxyBeanMethods = false)
    @Import(TargetConnectionTestConfiguration.class)
    static class Needs {
        static volatile int port;

        @Bean
        TargetConnectionRows targetConnectionRows() {
            return (TargetConnectionRows) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {TargetConnectionRows.class}, (p, m, a) -> Optional.of(
                            new TargetConnectionRecord(ORG, TARGET, "127.0.0.1", port, "sm", PIN, UUID.randomUUID(),
                                    "/srv/sm", "sm-", UUID.randomUUID(), Instant.EPOCH, null)));
        }

        @Bean
        SecretResolver secretResolver() {
            return (tenant, id, purpose) -> {
                throw new IllegalStateException("the strict policy must refuse before any key is opened");
            };
        }
    }

    @Test
    void theBeanIsTheSshTesterOverTheStrictPolicyAndAnAddressItRefusesIsNeverConnectedTo() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        listener.accept().close();
                        accepted.incrementAndGet();
                    }
                } catch (java.io.IOException closed) {
                    // the listener was closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            Needs.port = listener.getLocalPort();
            new ApplicationContextRunner().withUserConfiguration(Needs.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                        TargetConnectionTester tester = context.getBean(TargetConnectionTester.class);
                        assertInstanceOf(SshTargetConnectionTester.class, tester);

                        TargetConnectionTestResult result = tester.test(ORG, TARGET);

                        assertFalse(result.hostKeyPinned());
                        assertFalse(result.authenticated());
                        assertFalse(result.dockerAvailable());
                    });
            Thread.sleep(200);
            assertEquals(0, accepted.get(), "the policy refused loopback before a socket was opened");
        }
    }
}
