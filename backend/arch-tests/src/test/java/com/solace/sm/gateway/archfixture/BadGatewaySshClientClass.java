package com.solace.sm.gateway.archfixture;

import net.schmizz.sshj.SSHClient;

/** Planted violation: the gateway holding an SSH client (rule {@code noHttpLayerHoldsATransport}). */
public final class BadGatewaySshClientClass {

    private SSHClient client;

    public SSHClient client() {
        return client;
    }
}
