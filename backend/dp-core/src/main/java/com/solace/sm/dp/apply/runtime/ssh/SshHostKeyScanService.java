package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionValidator;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * The data plane's host-key scan (plan 013 T1.2, D-REG section 7.1): the address policy first, then one SSH
 * connection per key algorithm to <b>the address the policy checked</b>, each aborted by the verifier the moment the
 * host presents its key. No authentication is attempted and no credential exists in this class.
 *
 * <p>A scan from the operator's own machine ({@link SshHostKeyScanner#scan}) is a different thing and has no policy;
 * this class is the only way the API reaches {@link SshHostKeyScanner#scanChecked}.
 */
public final class SshHostKeyScanService implements HostKeyScanService {

    private final TargetAddressPolicy addressPolicy;
    private final SshHostKeyScanner scanner;

    public SshHostKeyScanService(TargetAddressPolicy addressPolicy, SshHostKeyScanner scanner) {
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    @Override
    public List<OfferedHostKey> scan(String host, int port) {
        if (!TargetConnectionValidator.isHost(host) || port < 1 || port > 65535) {
            // the one host grammar (TargetConnectionValidator, V007's target_connection_host CHECK): a host V007 could never store (an IPv6 literal) is refused here
            throw new IllegalArgumentException("a scan needs a registrable host and a port between 1 and 65535");
        }
        InetAddress address;
        try {
            address = addressPolicy.connectable(host);
        } catch (UnknownHostException e) {
            throw new HostKeyScanUnavailableException();
        }
        List<OfferedHostKey> offered = scanner.scanChecked(address, port);
        if (offered.isEmpty()) {
            throw new HostKeyScanUnavailableException();
        }
        return offered;
    }
}
