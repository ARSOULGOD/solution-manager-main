package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * What the data plane may connect to when a caller named the host (plan 013 T1.2, D-REG section 7.3, {@code OR-121}
 * OQ-3 (d)). One policy, applied at registration and edit and again, on the stored host, immediately before every
 * connection a data-plane connector makes: the stored value is a <b>name</b>, and a name re-resolves on every use.
 *
 * <p><b>The test seam is a bean, not a property (R7).</b> Production wires {@link StrictTargetAddressPolicy} and
 * nothing else; a permissive implementation exists only in test sources and is wired by an integration test's own
 * configuration. An architecture rule in {@code arch-tests} fails the build if any main-source class other than the
 * strict one implements this interface, so no production setting can relax the rule.
 */
public interface TargetAddressPolicy {

    /**
     * Resolves {@code host} and checks every address it resolves to.
     *
     * @return the checked addresses, in the resolver's order; never empty
     * @throws TargetAddressRefusedException any resolved address is one the data plane may not connect to
     * @throws UnknownHostException          the name does not resolve (a reachability failure, not a policy one)
     */
    List<InetAddress> check(String host) throws UnknownHostException;

    /**
     * The address a connector must connect to: the first checked one, carrying {@code host} as its name. Connecting to
     * this object (rather than to the name) means the connection goes where the check looked, and a client that
     * reports the remote name, such as sshj for its host-key verifier, still reports the name that was registered
     * (MF-R4, measured in {@code SshHostKeyScannerAddressTest}).
     *
     * <p>Abstract on purpose, so that this is not a functional interface: a lambda implementing a policy is invisible
     * to the {@code arch-tests} rule that lets only {@link StrictTargetAddressPolicy} implement it in main sources,
     * whereas a class, named or anonymous, is not.
     */
    InetAddress connectable(String host) throws UnknownHostException;
}
