package com.solace.sm.dp.inventory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Test sources only: a policy whose check is a function the test supplies. {@link TargetAddressPolicy} is deliberately
 * not a functional interface (a lambda would be invisible to the arch rule), so a test that wants a one-liner uses this.
 */
public final class StubTargetAddressPolicy implements TargetAddressPolicy {

    @FunctionalInterface
    public interface Check {
        List<InetAddress> of(String host) throws UnknownHostException;
    }

    private final Check check;

    public StubTargetAddressPolicy(Check check) {
        this.check = check;
    }

    @Override
    public List<InetAddress> check(String host) throws UnknownHostException {
        return check.of(host);
    }

    @Override
    public InetAddress connectable(String host) throws UnknownHostException {
        return InetAddress.getByAddress(host, check(host).get(0).getAddress());
    }
}
