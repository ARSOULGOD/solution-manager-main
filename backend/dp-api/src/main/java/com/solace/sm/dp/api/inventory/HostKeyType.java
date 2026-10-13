package com.solace.sm.dp.api.inventory;

import java.util.Optional;

/**
 * The type of an SSH host key, as {@code ssh-keygen -l} and the scan report it: the one stable thing about a key (OR-125 (2)).
 * A pin is the fingerprint of ONE key and so belongs to one type for good; recording the type beside the fingerprint lets a
 * connector negotiate that type directly instead of finding it out. Only the types the data plane's scan can offer are here
 * (it asks for Ed25519, ECDSA over the three NIST curves, and RSA); any other name is {@link #ofWire absent}, which a caller
 * treats as "type unknown", never as an error.
 *
 * <p>{@link #wire()} is the spelling {@code sm_data.target_connection.host_key_algorithm} stores (a CHECK holds exactly
 * this set) and {@code OfferedHostKey.algorithm} reports.
 */
public enum HostKeyType {

    ED25519("ssh-ed25519"),
    ECDSA_NISTP256("ecdsa-sha2-nistp256"),
    ECDSA_NISTP384("ecdsa-sha2-nistp384"),
    ECDSA_NISTP521("ecdsa-sha2-nistp521"),
    /** One RSA host key answers {@code rsa-sha2-512}, {@code rsa-sha2-256} and {@code ssh-rsa}; the key type is one. */
    RSA("ssh-rsa");

    private final String wire;

    HostKeyType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** The type spelt {@code name}, or empty for a name the data plane does not pin by type (including {@code null}). */
    public static Optional<HostKeyType> ofWire(String name) {
        for (HostKeyType type : values()) {
            if (type.wire.equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
