package com.solace.sm.dp.inventory.archfixture;

import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Planted violation (plan 013 T1.2, R7): a main-source class that implements the address policy and accepts anything.
 * It stands for the shortcut the rule exists to stop: a "local development" policy shipped in production code, switched
 * by a property. Never executed: it exists only to be analysed. <b>Do not copy this pattern into production code.</b>
 */
public final class BadPermissiveTargetAddressPolicy implements TargetAddressPolicy {

    @Override
    public List<InetAddress> check(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }

    @Override
    public InetAddress connectable(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }
}
