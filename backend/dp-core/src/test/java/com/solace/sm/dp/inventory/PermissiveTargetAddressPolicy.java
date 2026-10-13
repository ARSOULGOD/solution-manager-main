package com.solace.sm.dp.inventory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Test sources only (plan 013 T1.2, R7). Accepts whatever the name resolves to, loopback included, because
 * Testcontainers and the stub servers in the integration tests live there. It is wired by a test's own
 * configuration ({@link LoopbackTargetAddressConfiguration}); {@code arch-tests} fails the build if a main-source class
 * other than {@link StrictTargetAddressPolicy} implements the interface.
 */
public final class PermissiveTargetAddressPolicy implements TargetAddressPolicy {

    @Override
    public List<InetAddress> check(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }

    @Override
    public InetAddress connectable(String host) throws UnknownHostException {
        return InetAddress.getByAddress(host, check(host).get(0).getAddress());
    }
}
