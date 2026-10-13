package com.solace.sm.dp.inventory.archfixture;

import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.net.InetAddress;
import java.util.List;

/**
 * Planted violation (plan 013 T1.2, R7): the same shortcut as an anonymous class, which is how it would be written
 * inline in a {@code @Bean} method. (A lambda cannot implement the policy: it is deliberately not a functional
 * interface, because a lambda is invisible to the rule.) Never executed: it exists only to be analysed.
 */
public final class BadAnonymousTargetAddressPolicy {

    private BadAnonymousTargetAddressPolicy() {
    }

    public static TargetAddressPolicy anything() {
        return new TargetAddressPolicy() {
            @Override
            public List<InetAddress> check(String host) throws java.net.UnknownHostException {
                return List.of(InetAddress.getAllByName(host));
            }

            @Override
            public InetAddress connectable(String host) throws java.net.UnknownHostException {
                return InetAddress.getByName(host);
            }
        };
    }
}
